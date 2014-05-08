/*
* Licensed Materials - Property of IBM Corp.
* IBM UrbanCode Build
* IBM UrbanCode Deploy
* IBM UrbanCode Release
* IBM AnthillPro
* (c) Copyright IBM Corporation 2002, 2013. All Rights Reserved.
*
* U.S. Government Users Restricted Rights - Use, duplication or disclosure restricted by
* GSA ADP Schedule Contract with IBM Corp.
*/
package com.urbancode.air.plugin.wmbcmp;

import com.ibm.broker.config.proxy.*;
import java.util.Properties;
import java.util.Enumeration;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class WMBHelper {

    String host;
    int port;
    String channel;
    String queueManager;
    String executionGroup;
    BrokerConnectionParameters bcp;
    BrokerProxy brokerProxy;
    ExecutionGroupProxy executionGroupProxy;
    boolean isIncremental = true;
    def logProxy;
    Date startTime;
    def timeout;
    boolean isDebugEnabled = false;

    public WMBHelper(Properties props) {
        if (props['username']?.trim()) {
            System.out.println("Setting user.name to " + props['username']);
            System.setProperty("user.name", props['username']);
        }
        host = props['brokerHost'];
        port = Integer.valueOf(props['port']);
        channel = props['channel']?.trim();
        queueManager = props['queueManager'];
        executionGroup = props['executionGroup'];
        timeout = Long.valueOf(props['timeout']?.trim()?:60000);

        bcp = new MQBrokerConnectionParameters(host, port, queueManager);

        if (channel) {
           bcp.setAdvancedConnectionParameters(channel, null,null, -1, -1, null);
        }

        if (props['debugFile']) {
            isDebugEnabled = true;
            BrokerProxy.enableAdministrationAPITracing(props['debugFile']);
        }

        brokerProxy = BrokerProxy.getInstance(bcp);
        if (executionGroup != null && executionGroup.trim() != "") {
            executionGroupProxy = brokerProxy.getExecutionGroupByName(executionGroup);
        }

        if (Boolean.valueOf(props['fullDeploy'])) {
            isIncremental = false;
        }
        
        startTime = new Date(System.currentTimeMillis());
        logProxy = brokerProxy.getLog();
    }
   
    public void stopAllMsgFlows() {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }

        if (executionGroupProxy == null) {
            throw new IllegalStateException("Execution group proxy is null! Make sure it is configured correctly!");
        }
   
        executionGroupProxy.stopMessageFlows();
    }

    public void createExecutionGroupIfNeccessary() {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }

        String name = executionGroup;
        if (executionGroup == null || executionGroup.trim() == "") {
            throw new IllegalStateException("Tryed creating execution group with blank or null name.");
        }

        if (executionGroupProxy == null) {
            System.out.println("Execution group ${executionGroup} does not exist. Attempting to create...");
            executionGroupProxy = brokerProxy.createExecutionGroup(executionGroup);
            if (executionGroupProxy == null) {
                throw new RuntimeException("Could not create execution group with name ${name}");
            }
            System.out.println("Execution group ${executionGroup} created.");
        }
        else {
            System.out.println("Execution group ${executionGroup} exists. Skipping create...");
        }
    }

    public void deleteConfigurableService(String servType, String servName) {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }
        System.out.println("Deleting Configurable Service '${servName}' of type '${servType}'");
        brokerProxy.deleteConfigurableService(servType, servName);
    }

    public void createOrUpdateConfigurableService(String servType, String servName, Map<String,String> propsMap) {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }

        ConfigurableService service = brokerProxy.getConfigurableService(null, servName);
        if (service == null) {
            createConfigurableService(servType, servName, propsMap);
        }
        else {
            if (servType !=  service.getType()) {
                StringBuilder errMsg = new StringBuilder();
                errMsg.append("Cannot change the type of a configuable service.");
                errMsg.append("\n\tService Name : ").append(servName);
                errMsg.append("\n\tRequested Type : ").append(servType);
                errMsg.append("\n\tCurrent Type : ").append(service.getType());
                throw new IllegalStateException(errMsg)
            }
            updateConfigurableService(service, propsMap, servName, servType);
        }
    }

    private void createConfigurableService(String servType, String servName, Map<String,String>propsMap) {
        println "Creating configurable service '${servName}' of type '${servType}'"
        brokerProxy.createConfigurableService(servType, servName);
        deployBrokerConfig();
        ConfigurableService service = brokerProxy.getConfigurableService(null, servName);
        propsMap.each { key, value ->
            println "Setting property '${key}' = '${value}'";
            service.setProperty(key, value);
        }
    }

    private void updateConfigurableService(ConfigurableService service, Map<String,String>propsMap, String servName, String servType) {
        println "Updating configurable service '${servName}' of type '${servType}'"
        def keysToDelete = [];
        service.getProperties().each { key,value ->
            if (!propsMap.containsKey(key)) {
                println "Deleting property no longer in property map : '${key}' : '${value}'"
                keysToDelete << key;
            }
        }

        service.deleteProperties(keysToDelete as String[]);
        propsMap.each { key, value ->
            println "Setting property '${key}' = '${value}'";
            service.setProperty(key, value);
        }
    }

    public void startAllMsgFlows() {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }

        if (executionGroupProxy == null) {
            throw new IllegalStateException("Execution group proxy is null! Make sure it is configured correctly!");
        }

        executionGroupProxy.startMessageFlows();
    }

    public void deployBrokerArchive(String fileName) {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }

        if (executionGroupProxy == null) {
            throw new IllegalStateException("Execution group proxy is null! Make sure it is configured correctly!");
        }
        
        println "Using timeout ${timeout}";
        DeployResult dr = executionGroupProxy.deploy(fileName, isIncremental, timeout);
        
        if (dr.getCompletionCode() != CompletionCodeType.success) {
            checkDeployResult(dr);
            String code = "unknown";
            if (dr.getCompletionCode() == CompletionCodeType.failure) {
                code = "failure";
            }
            else if (dr.getCompletionCode() == CompletionCodeType.cancelled) {
                code = "cancelled";
            }
            else if (dr.getCompletionCode() == CompletionCodeType.pending) {
                code = "pending";
            }
            else if (dr.getCompletionCode() == CompletionCodeType.submitted) {
                code = "submitted";
            }
            throw new Exception("Failed deploying bar File ${fileName} with completion code : " + code);
        }

        checkDeployResult(dr);
    }
    
    public String[] getMessageFlowsFromProperties(props) {
        //todo
        
    }
    
    public String[] getMessageFlowsFromBarFile(String fileName) {
        //todo
        
    }
    
    public void startMsgFlow(String msgFlowName) {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }

        if (executionGroupProxy == null) {
            throw new IllegalStateException("Execution group proxy is null! Make sure it is configured correctly!");
        }
        
        MessageFlowProxy msgFlowProxy = executionGroupProxy.getMessageFlowByName(msgFlowName);
        if ( msgFlowProxy == null ) {
            throw new Exception("could not get message flow to start!");
        }
        msgFlowProxy.start();
    }
    
    public void stopMsgFlow(String msgFlowName) {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }

        if (executionGroupProxy == null) {
            throw new IllegalStateException("Execution group proxy is null! Make sure it is configured correctly!");
        }
        
        MessageFlowProxy msgFlowProxy = executionGroupProxy.getMessageFlowByName(msgFlowName);
        if ( msgFlowProxy == null ) {
            throw new Exception("could not get message flow to start!");
        }
        msgFlowProxy.stop();
        
    }
    
    public void setBrokerProperty(String name, String value) {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }
        
        String oldVal = brokerProxy.getRuntimeProperty(name);
        println "Setting property ${name} to ${value} from ${oldVal} on broker!";
        brokerProxy.setRuntimeProperty(name, value);
    }
    
    public void setExecutionGroupProperty(String name, String value) {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }
        
        if (executionGroupProxy == null) {
            throw new IllegalStateException("Execution group proxy is null! Make sure it is configured correctly!");
        }
        
        String oldVal = executionGroupProxy.getRuntimeProperty(name);
        println "Setting property ${name} to ${value} from ${oldVal} on Execution Group ${executionGroup}!";
        executionGroupProxy.setRuntimeProperty(name, value);
    }
    
    public void setMsgFlowProperty(String msgFlowName, String name, String value) {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }
        
        if (executionGroupProxy == null) {
            throw new IllegalStateException("Execution group proxy is null! Make sure it is configured correctly!");
        }
        
        MessageFlowProxy msgFlowProxy = executionGroupProxy.getMessageFlowByName(msgFlowName);
        if ( msgFlowProxy == null ) {
            throw new Exception("could not get message flow to set property on!");
        }
        
        String oldVal = msgFlowProxy.getRuntimeProperty(name);
        println "Setting property ${name} to ${value} from ${oldVal} on Message Flow ${msgFlowName} in Execution Group ${executionGroup}!";
        msgFlowProxy.setRuntimeProperty(name, value);
    }
    
    public void deployBrokerConfig() {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }
        brokerProxy.deploy(60000);
        checkDeployResult();
    }
    
    public void deployExecutionGroupConfig() {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }
        
        if (executionGroupProxy == null) {
            throw new IllegalStateException("Execution group proxy is null! Make sure it is configured correctly!");
        }
        
        brokerProxy.deploy(60000);
        checkDeployResult();
    }
    
	public void deleteMessageFlowsMatchingRegex(String regex) {
		if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }
        
        if (executionGroupProxy == null) {
            throw new IllegalStateException("Execution group proxy is null! Make sure it is configured correctly!");
        }
		
		// Compile the regex 
		Pattern p = Pattern.compile(regex);
		
		// List the flow names from the current BAR file
		Set<DeployedObject> flowsToDelete = new HashSet<DeployedObject>();
		
        // Get an unfiltered enumeration of all message flows in this execution group
        Enumeration<DeployedObject> allDeployedObjectsInThisEG = executionGroupProxy.getDeployedObjects();
        while (allDeployedObjectsInThisEG.hasMoreElements()) {
            DeployedObject thisDeployedObject = allDeployedObjectsInThisEG.nextElement();
            String barFileUsed = thisDeployedObject.getBARFileName();
            System.out.print(thisDeployedObject.getFullName() +" was deployed with BAR file ");
            System.out.print(barFileUsed);
            if ( (barFileUsed != null) && (p.matcher(barFileUsed).matches()) ){
                System.out.println(". Regex matched, adding flow for deletion...");
                flowsToDelete.add((Object)thisDeployedObject);
            } else {
                System.out.println(". Regex not matched, skipping...");
			}
		}
		
		if ( flowsToDelete.size() > 0) {
            println "Deleting "+flowsToDelete.size()+" deployed objects that are orphaned";
			println "Using timeout ${timeout}";
			
			// convert to DeployedObject [] to match deleteDeployedObjects method spec
			DeployedObject [] flowsToDeleteArray = new DeployedObject[flowsToDelete.size()];
            Iterator<DeployedObject> flowsIterator = flowsToDelete.iterator();

			int count = 0;
            while (flowsIterator.hasNext()) {
                flowsToDeleteArray[count++] = flowsIterator.next(); 
			}			

			executionGroupProxy.deleteDeployedObjects (flowsToDeleteArray , timeout);
			checkDeployResult();
		} 
        else {
			System.out.println("No orphaned flows to delete");
		}

	}
	
    public void deployMsgFlowConfig(String msgFlowName) {
        if (brokerProxy == null || bcp == null) {
            throw new IllegalStateException("Broker Proxy is uninitilized!");
        }
        
        if (executionGroupProxy == null) {
            throw new IllegalStateException("Execution group proxy is null! Make sure it is configured correctly!");
        }
        
        MessageFlowProxy msgFlowProxy = executionGroupProxy.getMessageFlowByName(msgFlowName);
        if ( msgFlowProxy == null ) {
            throw new Exception("could not get message flow to set property on!");
        }
        
        brokerProxy.deploy(60000);
        checkDeployResult();
    }
    
    public void checkDeployResult() {
        checkDeployResult(null);
    }
    public void checkDeployResult(def deployResult) {
        Enumeration logEntries = null;

        if (deployResult) {
            logEntries = deployResult.getDeployResponses();
        }
        else {
            logEntries = logProxy.elements();
        }

        def errors = [];

        while (logEntries.hasMoreElements()) {
            LogEntry logEntry = logEntries.nextElement();
            if (logEntry.isErrorMessage() && logEntry.getTimestamp() > startTime) {
                errors << logEntry.getTimestamp().toString() + " - " + logEntry.getMessage() + 
                        " : " + logEntry.getDetail();
            }
/*
            errors << logEntry.getTimestamp().toString() + " - " + logEntry.getMessage() + 
                    " : " + logEntry.getDetail();
*/
        }
        if (!errors.isEmpty()) {
            println "Errors during deployment";
            errors.each { error ->
                println error;
            }
            throw new Exception("Error during deployment");
        }
    }

    public void cleanUp() {
        if (isDebugEnabled) {
            BrokerProxy.disableAdministrationAPITracing();
        }
    }
	
	
}
