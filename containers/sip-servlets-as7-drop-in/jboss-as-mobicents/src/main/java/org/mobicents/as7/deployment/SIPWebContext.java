/*
 * TeleStax, Open Source Cloud Communications  Copyright 2012. 
 * and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.mobicents.as7.deployment;

import static org.mobicents.as7.SipMessages.MESSAGES;

import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.catalina.LifecycleException;
import org.jboss.as.clustering.web.DistributedCacheManagerFactory;
import org.jboss.as.clustering.web.sip.DistributedConvergedCacheManagerFactoryService;
import org.jboss.as.controller.PathElement;
import org.jboss.as.ee.structure.DeploymentType;
import org.jboss.as.ee.structure.DeploymentTypeMarker;
import org.jboss.as.server.deployment.AttachmentKey;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.web.deployment.WarMetaData;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.jboss.metadata.merge.web.jboss.JBossWebMetaDataMerger;
import org.jboss.metadata.web.jboss.JBossWebMetaData;
import org.jboss.metadata.web.spec.ListenerMetaData;
import org.jboss.metadata.web.spec.ServletMetaData;
import org.jboss.metadata.web.spec.SessionConfigMetaData;
import org.jboss.metadata.web.spec.WebMetaData;
import org.jboss.modules.Module;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistryException;
import org.jboss.msc.service.ServiceBuilder.DependencyType;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.vfs.VirtualFile;
import org.mobicents.as7.SipDeploymentDefinition;
import org.mobicents.as7.SipServer;
import org.mobicents.as7.SipSubsystemServices;
import org.mobicents.metadata.sip.jboss.JBossConvergedSipMetaData;
import org.mobicents.metadata.sip.merge.JBossSipMetaDataMerger;
import org.mobicents.metadata.sip.spec.ProxyConfigMetaData;
import org.mobicents.metadata.sip.spec.Sip11MetaData;
import org.mobicents.metadata.sip.spec.SipAnnotationMetaData;
import org.mobicents.metadata.sip.spec.SipMetaData;
import org.mobicents.metadata.sip.spec.SipServletSelectionMetaData;
import org.mobicents.metadata.sip.spec.SipServletsMetaData;
import org.mobicents.servlet.sip.core.SipService;
import org.mobicents.servlet.sip.startup.SipStandardContext;
import org.mobicents.servlet.sip.startup.jboss.SipJBossContextConfig;

/**
 * The SIP specific implementation of the jboss-web {@code StandardContext}.
 *
 *
 * @author Emanuel Muckenhuber
 * @author josemrecio@gmail.com
 */
public class SIPWebContext extends SipStandardContext {

    static AttachmentKey<SIPWebContext> ATTACHMENT = AttachmentKey.create(SIPWebContext.class);

    private static final Logger logger = Logger.getLogger(SIPWebContext.class);

    private final DeploymentUnit deploymentUnit;
    private SipJBossContextConfig sipJBossContextConfig;

    public SIPWebContext(DeploymentUnit du) {
        super();
        deploymentUnit = du;
        sipJBossContextConfig = createContextConfig(this, deploymentUnit);
        // attach context to top-level deploymentUnit so it can be used to get context resources (SipFactory, etc.)
        final DeploymentUnit anchorDu = getSipContextAnchorDu(du);
        if (anchorDu != null) {
        	if (logger.isDebugEnabled()) logger.debug("Attaching SIPWebContext " + this + " to " + anchorDu.getName() + ". Deployment unit name: " + deploymentUnit.getName());
        	anchorDu.putAttachment(SIPWebContext.ATTACHMENT, this);
        }
        else {
        	logger.error("Can't attach SIPWebContext " + this + " to " + deploymentUnit.getName() + " - This is probably a bug");
        }
//        DeploymentUnit parentDu = deploymentUnit.getParent();
//        if (parentDu == null) {
//        	// this is a war only deployment
//        	if (logger.isDebugEnabled()) logger.debug("Attaching SIPWebContext " + this + " to " + deploymentUnit.getName());
//        	deploymentUnit.putAttachment(SIPWebContext.ATTACHMENT, this);
//        }
//        else if (DeploymentTypeMarker.isType(DeploymentType.EAR, parentDu)) {
//        	if (logger.isDebugEnabled()) logger.debug("Attaching SIPWebContext " + this + " to " + parentDu.getName());
//        	parentDu.putAttachment(SIPWebContext.ATTACHMENT, this);
//        }
//        else {
//        	logger.error("Cowardly refusing to attach SIPWebContext " + this + " to " + deploymentUnit.getName() + " - This is probably a bug");
//        }
    }

    public void postProcessContext(DeploymentUnit deploymentUnit) {
    	if (logger.isDebugEnabled()){ 
    		logger.debug("postProcessContext");
    	}
    	
    	//-----------
    	/*final VirtualFile deploymentRoot = deploymentUnit.getAttachment(Attachments.DEPLOYMENT_ROOT).getRoot();
        final Module module = deploymentUnit.getAttachment(Attachments.MODULE);
        if (module == null) {
            throw new DeploymentUnitProcessingException(MESSAGES.failedToResolveModule(deploymentRoot));
        }
        final SipMetaData sipMetaData = deploymentUnit.getAttachment(SipMetaData.ATTACHMENT_KEY);
        if(sipMetaData != null) {
	        final String appNameMgmt = sipMetaData.getApplicationName();
	    	final ServiceName deploymentServiceName = SipSubsystemServices.deploymentServiceName(appNameMgmt);
	        try {
	        	final SipDeploymentService sipDeploymentService = new SipDeploymentService(deploymentUnit);
	        	ServiceBuilder<?> builder = serviceTarget
	        			.addService(deploymentServiceName, sipDeploymentService);
	
	        	if (logger.isDebugEnabled()){
	        		logger.debug("processDeployment - start distributable stuff");
	        	}
	//        	TODO: when distributable is implemented
	        	if (sipMetaData.getDistributable() != null) {
	        		if (logger.isDebugEnabled()){
		        		logger.debug("processDeployment - distributable");
		        	}
	        		//DistributedCacheManagerFactoryService factoryService = new DistributedCacheManagerFactoryService();
	        		//DistributedCacheManagerFactory factory = factoryService.getValue();
	        		final DistributedCacheManagerFactory factory = new DistributedConvergedCacheManagerFactoryService().getValue();
	                
	        		if (factory != null) {
	        			ServiceName factoryServiceName = deploymentServiceName.append("session");
	        			builder.addDependency(DependencyType.OPTIONAL, factoryServiceName, DistributedCacheManagerFactory.class, sipJBossContextConfig.getDistributedCacheManagerFactoryInjector());
	
	        			ServiceBuilder<DistributedCacheManagerFactory> factoryBuilder = serviceTarget.addService(factoryServiceName, factoryService);
	        			boolean enabled = factory.addDeploymentDependencies(deploymentServiceName, deploymentUnit.getServiceRegistry(), serviceTarget, factoryBuilder, metaData);
	        			factoryBuilder.setInitialMode(enabled ? ServiceController.Mode.ON_DEMAND : ServiceController.Mode.NEVER).install();
	        		}
	        	}
	        // TODO - VEGE, eddig tart a distributable-s resz
	        	
	        	// add dependency to sip deployment service
	            builder.addDependency(deploymentServiceName);
	            builder.setInitialMode(Mode.ACTIVE).install();
	        } catch (ServiceRegistryException e) {
	        	throw new DeploymentUnitProcessingException(MESSAGES.failedToAddSipDeployment(), e);
	        }        

	        // Process sip related mgmt information
	        final ModelNode node = deploymentUnit.getDeploymentSubsystemModel("sip");
	        node.get(SipDeploymentDefinition.APP_NAME.getName()).set("".equals(appNameMgmt) ? "/" : appNameMgmt);
	        processManagement(deploymentUnit, sipMetaData);
        } else {
        	
        }*/
    	//----------
    	
    }

    void processManagement(final DeploymentUnit unit, final SipMetaData sipMetaData) {
    	if(logger.isDebugEnabled()) {
    		logger.debug("processManagement - " + deploymentUnit.getName());
    	}
    	if(sipMetaData.getSipServlets() != null) {
	        for (final ServletMetaData servlet : sipMetaData.getSipServlets()) {
	            try {
	                final String name = servlet.getName().replace(' ', '_');
	                final ModelNode node = unit.createDeploymentSubModel("sip", PathElement.pathElement("servlet", name));
	                node.get("servlet-class").set(servlet.getServletClass());
	                node.get("servlet-name").set(servlet.getServletName());
	                node.get("load-on-startup").set(servlet.getLoadOnStartup());
	            } catch (Exception e) {
	                // Should a failure in creating the mgmt view also make to the deployment to fail?
	                continue;
	            }
	        }
    	}
    }
    
    @Override
    public void init() throws Exception {
    	if(logger.isDebugEnabled()) {
    		logger.debug("init - " + deploymentUnit.getName());
    	}
        SipServer sipServer = deploymentUnit.getAttachment(SipServer.ATTACHMENT_KEY);
        if (sipServer.getService() instanceof SipService) {
            super.sipApplicationDispatcher = ((SipService)sipServer.getService()).getSipApplicationDispatcher();
        }
        super.init();
    }

    @Override
    public void start() throws LifecycleException {
    	if(logger.isDebugEnabled()) {
    		logger.debugf("Starting sip web context for deployment %s", deploymentUnit.getName());
    	}
        SipMetaData sipMetaData = deploymentUnit.getAttachment(SipMetaData.ATTACHMENT_KEY);
        SipAnnotationMetaData sipAnnotationMetaData = deploymentUnit.getAttachment(SipAnnotationMetaData.ATTACHMENT_KEY);

        JBossWebMetaData mergedMetaData = null;
        mergedMetaData = new JBossConvergedSipMetaData();
        final WarMetaData warMetaData = deploymentUnit.getAttachment(WarMetaData.ATTACHMENT_KEY);
        final JBossWebMetaData override = warMetaData.getJBossWebMetaData();
        final WebMetaData original = null;
        JBossWebMetaDataMerger.merge(mergedMetaData, override, original);

        if(logger.isDebugEnabled()) {
    		logger.debugf("security domain " + mergedMetaData.getSecurityDomain() + " for deployment %s", deploymentUnit.getName());
    	}
        if(sipMetaData == null && sipAnnotationMetaData != null && sipAnnotationMetaData.isSipApplicationAnnotationPresent()) {
        	// http://code.google.com/p/sipservlets/issues/detail?id=168
        	// When no sip.xml but annotations only, Application is not recognized as SIP App by AS7
        	logger.debugf("sip meta data is null, creating a new one");
        	sipMetaData = new Sip11MetaData();
        }
        augmentAnnotations(mergedMetaData, sipMetaData, sipAnnotationMetaData);
        try {
			processMetaData(mergedMetaData, sipMetaData);
		} catch (Exception e) {
			if(logger.isDebugEnabled()) {
	    		logger.error("An unexpected exception happened while parsing sip meta data from (" + deploymentUnit.getName() + "): ", e);
	    	}
			throw new LifecycleException("An unexpected exception happened while parsing sip meta data from " + deploymentUnit.getName(), e);
		}

        super.start();
    }

    private void augmentAnnotations(JBossWebMetaData mergedMetaData, SipMetaData sipMetaData, SipAnnotationMetaData sipAnnotationMetaData) throws LifecycleException {
    	// https://github.com/Mobicents/sip-servlets/issues/68 iterating through all entry set and not only classes directory
    	Set<Entry<String, SipMetaData>> annotationsEntrySet = sipAnnotationMetaData.entrySet();
        if (logger.isDebugEnabled()) {
        	logger.debug("sipAnnotationMetaData " + sipAnnotationMetaData);
            if (sipAnnotationMetaData != null) {
            	for(Entry<String, SipMetaData> annotationEntry : annotationsEntrySet) {
            		String annotatedSipMetaDataKey = annotationEntry.getKey();
            		SipMetaData annotatedSipMetaData = annotationEntry.getValue();
            		logger.debug("sipAnnotationMetaDataKey " + annotatedSipMetaDataKey + " value " + annotatedSipMetaData);
	                if (annotatedSipMetaData.getListeners() != null) {
	                    for (ListenerMetaData listenerMetaData: annotatedSipMetaData.getListeners()) {
	                        if (logger.isDebugEnabled()) logger.debug("@SipListener: " + listenerMetaData.getListenerClass() + " in " + annotatedSipMetaDataKey);
	                    }
	                }
	                if (annotatedSipMetaData.getSipServlets() != null) {
	                    for (ServletMetaData sipServletMetaData: annotatedSipMetaData.getSipServlets()) {
	                        if (logger.isDebugEnabled()) logger.debug("@SipServlet: " + sipServletMetaData.getServletClass() + " in " + annotatedSipMetaDataKey);
	                    }
	                }
            	}
            }
        }
        // merging sipMetaData and clumsy sip annotation processing
        
        if (logger.isDebugEnabled()) {
            logger.debug("<Before clumsy augmentation>");
            if (sipMetaData.getListeners() != null) {
                logger.debug("Listeners: " + sipMetaData.getListeners().size());
                for (ListenerMetaData check : sipMetaData.getListeners()) {
                    logger.debug("Listener: " + check.getListenerClass());
                }
            }
            if (sipMetaData.getSipServlets() != null) {
                logger.debug("SipServlets: " + sipMetaData.getSipServlets().size());
                for (ServletMetaData check: sipMetaData.getSipServlets()) {
                	logger.debug("SipServlet: " + check.getName() + " - class: " + check.getServletClass() + " - load-on-startup: " + check.getLoadOnStartup());
                }
            }
            logger.debug("</Before clumsy augmentation>");
        }
        // FIXME: josemrecio - clumsy annotation augmentation, this should be done by SipAnnotationMergedView or similar
        // FIXME: josemrecio - SipAnnotation is supported, full merge is needed (e.g. main servlet selection) but not done yet
        
        if (sipAnnotationMetaData != null) {
        	for(Entry<String, SipMetaData> annotationEntry : annotationsEntrySet) {
        		String annotatedSipMetaDataKey = annotationEntry.getKey();
        		SipMetaData annotatedSipMetaData = annotationEntry.getValue();

	            // @SipApplication processing
	            // existing sipMetaData overrides annotations
                // main servlet
                if (annotatedSipMetaData.getServletSelection() != null && annotatedSipMetaData.getServletSelection().getMainServlet() != null) {
                    if (sipMetaData.getServletSelection() == null) {
                        sipMetaData.setServletSelection(new SipServletSelectionMetaData());
                        sipMetaData.getServletSelection().setMainServlet(annotatedSipMetaData.getServletSelection().getMainServlet());
                    }
                }
                // proxy timeout
                if (annotatedSipMetaData.getProxyConfig() != null && annotatedSipMetaData.getProxyConfig().getProxyTimeout() != 0) {
                    if (sipMetaData.getProxyConfig() == null) {
                        sipMetaData.setProxyConfig(new ProxyConfigMetaData());
                        sipMetaData.getProxyConfig().setProxyTimeout(annotatedSipMetaData.getProxyConfig().getProxyTimeout());
                    }
                }
                // session timeout
                if (annotatedSipMetaData.getSessionConfig() != null && annotatedSipMetaData.getSessionConfig().getSessionTimeout() != 0) {
                    if (sipMetaData.getSessionConfig() == null) {
                        sipMetaData.setSessionConfig(new SessionConfigMetaData());
                        sipMetaData.getSessionConfig().setSessionTimeout(annotatedSipMetaData.getSessionConfig().getSessionTimeout());
                    }
                }
                // application name
                if (annotatedSipMetaData.getApplicationName() != null) {
                    if (sipMetaData.getApplicationName() == null) {
                        sipMetaData.setApplicationName(annotatedSipMetaData.getApplicationName());
                    }
                    else if (sipMetaData.getApplicationName().compareTo(annotatedSipMetaData.getApplicationName()) != 0) {
                        throw (new LifecycleException("Sip application name mismatch: " + sipMetaData.getApplicationName() + " (from sip.xml) vs " + annotatedSipMetaData.getApplicationName()+ " from annotations " + annotatedSipMetaDataKey));
                    }
                }
                // description
                if (annotatedSipMetaData.getDescriptionGroup() != null) {
                    if (sipMetaData.getDescriptionGroup() == null) {
                        sipMetaData.setDescriptionGroup(annotatedSipMetaData.getDescriptionGroup());
                    }
                }
                
                // distributable
                // TODO: josemrecio - distributable not supported yet
                if (logger.isDebugEnabled()) {
                	logger.debug("augmentAnnotations - TODO - distributable");
                }
                if (annotatedSipMetaData.getDistributable() != null) {
                	if (logger.isDebugEnabled()) {
                    	logger.debug("augmentAnnotations - TODO2 - distributable");
                    }	
                }
                
	            if (annotatedSipMetaData.getListeners() != null) {
	                if (sipMetaData.getListeners() == null) {
	                    sipMetaData.setListeners(new ArrayList<ListenerMetaData>());
	                }
	                for (ListenerMetaData listenerMetaData: annotatedSipMetaData.getListeners()) {
	                    boolean found = false;
	                    for (ListenerMetaData check : sipMetaData.getListeners()) {
	                        if (check.getListenerClass().equals(listenerMetaData.getListenerClass())) {
	                            if (logger.isDebugEnabled()) logger.debug("@SipListener already present: " + listenerMetaData.getListenerClass() + " from " + annotatedSipMetaDataKey);
	                            found = true;
	                        }
	                    }
	                    if (!found) {
	                        if (logger.isDebugEnabled()) logger.debug("Added @SipListener: " + listenerMetaData.getListenerClass() + " from " + annotatedSipMetaDataKey);
	                        sipMetaData.getListeners().add(listenerMetaData);
	                    }
	                }
	            }
	            if (annotatedSipMetaData.getSipServlets() != null) {
	                if (sipMetaData.getSipServlets() == null) {
	                    sipMetaData.setSipServlets(new SipServletsMetaData());
	                }
	                for (ServletMetaData servletMetaData: annotatedSipMetaData.getSipServlets()) {
	                    boolean found = false;
	                    for (ServletMetaData check : sipMetaData.getSipServlets()) {
	                        if (check.getServletClass().equals(servletMetaData.getServletClass())) {
	                            if (logger.isDebugEnabled()) logger.debug("@SipServlet already present: " + servletMetaData.getServletClass() + " from " + annotatedSipMetaDataKey);
	                            found = true;
	                        }
	                    }
	                    if (!found) {
	                        if (logger.isDebugEnabled()) logger.debug("Added @SipServlet: " + servletMetaData.getServletClass() + " from " + annotatedSipMetaDataKey);
	                        sipMetaData.getSipServlets().add(servletMetaData);
	                    }
	                }
	            }
	            if (annotatedSipMetaData.getSipApplicationKeyMethodInfo() != null) {
	            	sipMetaData.setSipApplicationKeyMethodInfo(annotatedSipMetaData.getSipApplicationKeyMethodInfo());
	            }
	            if (annotatedSipMetaData.getConcurrencyControlMode() != null) {
	            	if (sipMetaData.getConcurrencyControlMode() == null) {
	            		sipMetaData.setConcurrencyControlMode(annotatedSipMetaData.getConcurrencyControlMode());
	            	}
	            }
        	}
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("<After clumsy augmentation>");

            if (sipMetaData.getListeners() != null) {
                logger.debug("Listeners: " + sipMetaData.getListeners().size());
                for (ListenerMetaData check : sipMetaData.getListeners()) {
                    logger.debug("Listener: " + check.getListenerClass());
                }
            }
            if (sipMetaData.getSipServlets() != null) {
                logger.debug("SipServlets: " + sipMetaData.getSipServlets().size());
                for (ServletMetaData check: sipMetaData.getSipServlets()) {
                    logger.debug("SipServlet: " + check.getName() + " - class: " + check.getServletClass() + " - load-on-startup: " + check.getLoadOnStartup());
                }
            }
            logger.debug("</After clumsy augmentation>");
        }
        JBossSipMetaDataMerger.merge((JBossConvergedSipMetaData)mergedMetaData, null, sipMetaData);
    }

    private void processMetaData(JBossWebMetaData mergedMetaData, SipMetaData sipMetaData) throws Exception {
    	if (logger.isDebugEnabled()) {
            logger.debug("processMetaData");
        }
        //processJBossWebMetaData(sharedJBossWebMetaData);
        //processWebMetaData(sharedJBossWebMetaData);
    	
    	//???
    	/*if (logger.isDebugEnabled()) {
            logger.debug("new calls");
        }
    	sipJBossContextConfig.processJBossWebMetaData(mergedMetaData);
    	sipJBossContextConfig.processWebMetaData(mergedMetaData);
    	if (logger.isDebugEnabled()) {
            logger.debug("new calls ended");
        }*/
    	//???
    	
        JBossSipMetaDataMerger.merge((JBossConvergedSipMetaData)mergedMetaData, null, sipMetaData);
        sipJBossContextConfig.processSipMetaData((JBossConvergedSipMetaData)mergedMetaData);
    }

    private SipJBossContextConfig createContextConfig(SipStandardContext sipContext, DeploymentUnit deploymentUnit) {
    	if (logger.isDebugEnabled()){
        	logger.debug("createContextConfig - " + deploymentUnit.getName());
        }
        SipJBossContextConfig config = new SipJBossContextConfig(deploymentUnit);
        if (logger.isDebugEnabled()){
        	logger.debug("createContextConfig - created: " + config);
        }
        sipContext.addLifecycleListener(config);
        return config;
    }
    
    // returns the anchor deployment unit that will have attached a SIPWebContext
    public static DeploymentUnit getSipContextAnchorDu(final DeploymentUnit du) {
        // attach context to top-level deploymentUnit so it can be used to get context resources (SipFactory, etc.)
        DeploymentUnit parentDu = du.getParent();
        if (parentDu == null) {
        	// this is a war only deployment
        	return du;
        }
        else if (DeploymentTypeMarker.isType(DeploymentType.EAR, parentDu)) {
        	return parentDu;
        }
        else {
        	logger.error("Can't find proper anchor deployment unit for " + du.getName() + " - This is probably a bug");
        	return null;
        }
    }

	public SipJBossContextConfig getSipJBossContextConfig() {
		if (logger.isDebugEnabled()){
        	logger.debug("getSipJBossContextConfig - " + deploymentUnit.getName());
        }
		return sipJBossContextConfig;
	}
    
    

}
