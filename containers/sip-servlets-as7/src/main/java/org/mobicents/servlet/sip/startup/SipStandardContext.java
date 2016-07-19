/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package org.mobicents.servlet.sip.startup;

import static org.jboss.web.CatalinaMessages.MESSAGES;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.TimerService;

import org.apache.catalina.Container;
import org.apache.catalina.Engine;
import org.apache.catalina.Globals;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Manager;
import org.apache.catalina.Service;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.security.SecurityUtil;
import org.apache.log4j.Logger;
import org.apache.tomcat.InstanceManager;
import org.jboss.as.clustering.web.BatchingManager;
import org.jboss.as.clustering.web.OutgoingDistributableSessionData;
import org.jboss.as.clustering.web.sip.DistributedCacheConvergedSipManager;
import org.jboss.as.web.session.sip.ClusteredSipApplicationSession;
import org.jboss.as.web.session.sip.ClusteredSipSession;
import org.jboss.as.web.session.sip.ClusteredSipSessionManager;
import org.jboss.as.web.session.sip.ConvergedSessionReplicationContext;
import org.jboss.as.web.session.sip.SnapshotSipManager;
import org.mobicents.servlet.sip.SipConnector;
import org.mobicents.servlet.sip.annotation.ConcurrencyControlMode;
import org.mobicents.servlet.sip.catalina.CatalinaSipContext;
import org.mobicents.servlet.sip.catalina.CatalinaSipListenersHolder;
import org.mobicents.servlet.sip.catalina.CatalinaSipManager;
import org.mobicents.servlet.sip.catalina.ContextGracefulStopTask;
import org.mobicents.servlet.sip.catalina.SARDirContext;
import org.mobicents.servlet.sip.catalina.SipDeploymentException;
import org.mobicents.servlet.sip.catalina.SipSecurityConstraint;
import org.mobicents.servlet.sip.catalina.SipServletImpl;
import org.mobicents.servlet.sip.catalina.SipStandardManager;
import org.mobicents.servlet.sip.catalina.annotations.SipInstanceManager;
import org.mobicents.servlet.sip.catalina.security.SipSecurityUtils;
import org.mobicents.servlet.sip.catalina.security.authentication.DigestAuthenticator;
import org.mobicents.servlet.sip.core.MobicentsSipServlet;
import org.mobicents.servlet.sip.core.SipApplicationDispatcher;
import org.mobicents.servlet.sip.core.SipContextEvent;
import org.mobicents.servlet.sip.core.SipContextEventType;
import org.mobicents.servlet.sip.core.SipListeners;
import org.mobicents.servlet.sip.core.SipManager;
import org.mobicents.servlet.sip.core.SipService;
import org.mobicents.servlet.sip.core.descriptor.MobicentsSipServletMapping;
import org.mobicents.servlet.sip.core.message.MobicentsSipServletRequest;
import org.mobicents.servlet.sip.core.message.MobicentsSipServletResponse;
import org.mobicents.servlet.sip.core.security.MobicentsSipLoginConfig;
import org.mobicents.servlet.sip.core.security.SipDigestAuthenticator;
import org.mobicents.servlet.sip.core.session.DistributableSipManager;
import org.mobicents.servlet.sip.core.session.MobicentsSipApplicationSession;
import org.mobicents.servlet.sip.core.session.MobicentsSipSession;
import org.mobicents.servlet.sip.core.session.SipApplicationSessionCreationThreadLocal;
import org.mobicents.servlet.sip.core.session.SipSessionsUtilImpl;
import org.mobicents.servlet.sip.core.timers.DefaultProxyTimerService;
import org.mobicents.servlet.sip.core.timers.DefaultSipApplicationSessionTimerService;
import org.mobicents.servlet.sip.core.timers.FaultTolerantSasTimerService;
import org.mobicents.servlet.sip.core.timers.FaultTolerantTimerServiceImpl;
import org.mobicents.servlet.sip.core.timers.ProxyTimerService;
import org.mobicents.servlet.sip.core.timers.ProxyTimerServiceImpl;
import org.mobicents.servlet.sip.core.timers.SipApplicationSessionTimerService;
import org.mobicents.servlet.sip.core.timers.SipServletTimerService;
import org.mobicents.servlet.sip.core.timers.StandardSipApplicationSessionTimerService;
import org.mobicents.servlet.sip.core.timers.TimerServiceImpl;
import org.mobicents.servlet.sip.listener.SipConnectorListener;
import org.mobicents.servlet.sip.message.SipFactoryFacade;
import org.mobicents.servlet.sip.message.SipFactoryImpl;
import org.mobicents.servlet.sip.ruby.SipRubyController;

/**
 * Sip implementation of the <b>Context</b> interface extending the standard
 * tomcat context to allow deployment of converged applications (sip & web apps)
 * as well as standalone sip servlets applications.
 * 
 * @author Jean Deruelle
 * 
 */
public class SipStandardContext extends StandardContext implements CatalinaSipContext {
	
	private static final long serialVersionUID = 1L;
	//	 the logger
	private static final Logger logger = Logger.getLogger(SipStandardContext.class);
	private transient SipRubyController rubyController;

	/**
     * The descriptive information string for this implementation.
     */
    private static final String info =
        "org.mobicents.servlet.sip.startup.SipStandardContext/1.0";
    
	// as mentionned per JSR 289 Section 6.1.2.1 default lifetime for an 
	// application session is 3 minutes
	private static int DEFAULT_LIFETIME = 3;
	
	// default quotable params that their values need to be quoted.
	private static final String DEFAULT_QUOTABLE_PARAMS = "vendor, model, version, cnonce, nextnonce,"
			+ "nonce, code, oc-algo, cid, text, domain, opaque, qop, realm, response, rspauth, uri, username";
	
	protected String applicationName;
	protected String smallIcon;
	protected String largeIcon;
	protected String description;
	protected int proxyTimeout;
	protected int sipApplicationSessionTimeout;
	protected transient SipListeners sipListeners;	
	protected transient SipFactoryFacade sipFactoryFacade;	
	protected transient SipSessionsUtilImpl sipSessionsUtil;
	protected transient MobicentsSipLoginConfig sipLoginConfig;
	protected transient SipSecurityUtils sipSecurityUtils;
	protected transient SipDigestAuthenticator sipDigestAuthenticator;
	protected transient String securityDomain;
	
	protected boolean hasDistributableManager;
	
    protected String namingContextName;
    
    protected transient Method sipApplicationKeyMethod;
    protected ConcurrencyControlMode concurrencyControlMode;    
	/**
     * The set of sip application listener class names configured for this
     * application, in the order they were encountered in the sip.xml file.
     */
    protected transient List<String> sipApplicationListeners = new CopyOnWriteArrayList<String>();
    // Issue 1200 this is needed to be able to give a default servlet handler if we are not in main-servlet servlet selection case
    // by example when creating a new sip application session from a factory from an http servlet
    private String servletHandler;
    protected boolean isMainServlet;
    private String mainServlet;
    /**
     * The set of sip servlet mapping configured for this
     * application.
     */
    protected transient List<MobicentsSipServletMapping> sipServletMappings = new ArrayList<MobicentsSipServletMapping>();
    
    protected transient SipApplicationDispatcher sipApplicationDispatcher = null;
    
    protected transient Map<String, MobicentsSipServlet> childrenMap;
    protected transient Map<String, MobicentsSipServlet> childrenMapByClassName;

    // timer service used to schedule sip application session expiration timer
    protected transient SipApplicationSessionTimerService sasTimerService = null;
    // timer service used to schedule sip servlet originated timer tasks
    protected transient SipServletTimerService timerService = null;
    // timer service used to schedule proxy timer tasks
    protected transient ProxyTimerService proxyTimerService = null;  
    // http://code.google.com/p/mobicents/issues/detail?id=2450
    private transient ThreadLocal<SipApplicationSessionCreationThreadLocal> sipApplicationSessionsAccessedThreadLocal = new ThreadLocal<SipApplicationSessionCreationThreadLocal>();
    // http://code.google.com/p/mobicents/issues/detail?id=2534 && http://code.google.com/p/mobicents/issues/detail?id=2526
    private transient ThreadLocal<Boolean> isManagedThread = new ThreadLocal<Boolean>();
    // http://code.google.com/p/sipservlets/issues/detail?id=195
    private ScheduledFuture<?> gracefulStopFuture;
	/**
	 * 
	 */
	public SipStandardContext() {
		super();
		sipApplicationSessionTimeout = DEFAULT_LIFETIME;
		pipeline.setBasic(new SipStandardContextValve());
		sipListeners = new CatalinaSipListenersHolder(this);
		childrenMap = new HashMap<String, MobicentsSipServlet>();
		childrenMapByClassName = new HashMap<String, MobicentsSipServlet>();
		int idleTime = getSipApplicationSessionTimeout();
		if(idleTime <= 0) {
			idleTime = 1;
		}
		isMainServlet = false;
	}

	@Override
	public void init() throws Exception {
		if(logger.isDebugEnabled()) {
			logger.debug("Initializing the sip context");
		}
//		if (this.getParent() != null) {
//			// Add the main configuration listener for sip applications
//			LifecycleListener sipConfigurationListener = new SipContextConfig();
//			this.addLifecycleListener(sipConfigurationListener);			
//			setDelegate(true);
//		}				
		// call the super method to correctly initialize the context and fire
		// up the
		// init event on the new registered SipContextConfig, so that the
		// standardcontextconfig
		// is correctly initialized too
		super.init();
		
		prepareServletContext();
		
		if(logger.isDebugEnabled()) {
			logger.debug("sip context Initialized");
		}	
	}

	protected void prepareServletContext() throws LifecycleException {
		if(logger.isDebugEnabled()) {
			logger.debug("prepareServletContext " + getName());
		}
		if(sipApplicationDispatcher == null) {
			setApplicationDispatcher();
		}
		if(sipFactoryFacade == null) {
			sipFactoryFacade = new SipFactoryFacade((SipFactoryImpl)sipApplicationDispatcher.getSipFactory(), this);
		}
		if(sipSessionsUtil == null) {
			sipSessionsUtil = new SipSessionsUtilImpl(this);
		}
		//needed when restarting applications through the tomcat manager 
		this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.SIP_FACTORY,
				sipFactoryFacade);
		if(timerService == null) {
// FIXME: distributable not supported
			
			if(logger.isDebugEnabled()) {
				logger.debug("prepareServletContext - timerService is null" + getName());
			}
			
			if(getDistributable() && hasDistributableManager) {
				if(logger.isInfoEnabled()) {
					logger.info("Using the Fault Tolerant Timer Service to schedule fault tolerant timers in a distributed environment");
				}
				timerService = new FaultTolerantTimerServiceImpl((DistributableSipManager)getSipManager());
			} else {
				timerService = new TimerServiceImpl(sipApplicationDispatcher.getSipService(), applicationName);
			}
// FIXME - VEGE: distributable not supported
			
			
			timerService = new TimerServiceImpl(sipApplicationDispatcher.getSipService(), applicationName);
		}
		if(proxyTimerService == null) {
			if(logger.isDebugEnabled()) {
				logger.debug("prepareServletContext - proxyTimerService is null" + getName());
			}
			
			String proxyTimerServiceType = sipApplicationDispatcher.getSipService().getProxyTimerServiceImplementationType();
			if(proxyTimerServiceType != null && proxyTimerServiceType.equalsIgnoreCase("Standard")) {
				if(logger.isDebugEnabled()) {
					logger.debug("prepareServletContext - proxyTimerService is standard (new ProxyTimerServiceImpl) - " + getName());
				}
                proxyTimerService = new ProxyTimerServiceImpl(applicationName);
            } else if(proxyTimerServiceType != null && proxyTimerServiceType.equalsIgnoreCase("Default")) {
            	if(logger.isDebugEnabled()) {
					logger.debug("prepareServletContext - proxyTimerService is default (new DefaultProxyTimerService) - " + getName());
				}
                proxyTimerService = new DefaultProxyTimerService(applicationName);
            } else {
            	if(logger.isDebugEnabled()) {
					logger.debug("prepareServletContext - proxyTimerService else (new ProxyTimerServiceImpl) - " + getName());
				}
                proxyTimerService = new ProxyTimerServiceImpl(applicationName);
            }
		}
		if(sasTimerService == null || !sasTimerService.isStarted()) {
			String sasTimerServiceType = sipApplicationDispatcher.getSipService().getSasTimerServiceImplementationType();
			if(getDistributable() && hasDistributableManager) {
				sasTimerService = new FaultTolerantSasTimerService((DistributableSipManager)getSipManager(), 4);
			} else if (sasTimerServiceType != null && sasTimerServiceType.equalsIgnoreCase("Standard")) {
                sasTimerService = new StandardSipApplicationSessionTimerService(applicationName);
            } else if (sasTimerServiceType != null && sasTimerServiceType.equalsIgnoreCase("Default")) {
                sasTimerService = new DefaultSipApplicationSessionTimerService(applicationName);
            } else {
                sasTimerService = new StandardSipApplicationSessionTimerService(applicationName);
            }
		}
		this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.TIMER_SERVICE,
				timerService);
		this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.SUPPORTED,
				Arrays.asList(sipApplicationDispatcher.getExtensionsSupported()));
		this.getServletContext().setAttribute("javax.servlet.sip.100rel", Boolean.TRUE);
		this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.SUPPORTED_RFCs,
				Arrays.asList(sipApplicationDispatcher.getRfcSupported()));
		this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.SIP_SESSIONS_UTIL,
				sipSessionsUtil);
		this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.OUTBOUND_INTERFACES,
				sipApplicationDispatcher.getOutboundInterfaces());	
		this.getServletContext().setAttribute("org.mobicents.servlet.sip.SIP_CONNECTORS",
				sipApplicationDispatcher.getSipService().findSipConnectors());
		this.getServletContext().setAttribute("org.mobicents.servlet.sip.DNS_RESOLVER",
				sipApplicationDispatcher.getDNSResolver());
		this.getServletContext().setAttribute("org.restcomm.servlets.sip.QUOTABLE_PARAMETER", 
				getQuotableParams());
	}
	
	/**
	 * 
	 * @return a list of known params that their values need to be quoted.
	 */
	private List<String> getQuotableParams(){
		List<String> retValue = new ArrayList<String>();
		String quotableParameters = this.getServletContext().getInitParameter("org.restcomm.servlets.sip.QUOTABLE_PARAMETER");
		if (quotableParameters == null){
			quotableParameters = DEFAULT_QUOTABLE_PARAMS;
		}
		String[] parameters = quotableParameters.split(",");
		for (int i = 0; i < parameters.length; i++){
			String param = parameters[i].trim();
			if (param != null && !param.isEmpty() && !retValue.contains(param)){
				retValue.add(param);
			}
		}
		return retValue;
	}
	
	/**
	 * @throws Exception
	 */
	protected void setApplicationDispatcher() throws LifecycleException {
		Container container = getParent().getParent();
		if(container instanceof Engine) {
			Service service = ((Engine)container).getService();
			if(service instanceof SipService) {
				sipApplicationDispatcher = 
					((SipService)service).getSipApplicationDispatcher();
			}
		}
		if(sipApplicationDispatcher == null) {
			throw new LifecycleException("cannot find any application dispatcher for this context " + name);
		}
	}

	@Override
	public synchronized void start() throws LifecycleException {
		if(logger.isDebugEnabled()) {
			logger.debug("Starting the sip context " + getName());
		}
		if( initialized ) { 
			prepareServletContext();
		}	
		 // Add missing components as necessary
        if (getResources() == null) {   // (1) Required by Loader
            if (logger.isDebugEnabled())
                logger.debug("Configuring default Resources");
            try {
                if ((getDocBase() != null) && (getDocBase().endsWith(".sar")) && (!(new File(getBasePath())).isDirectory()))
                    setResources(new SARDirContext());                
            } catch (IllegalArgumentException e) {
                logger.error("Error initializing resources: " + e.getMessage());
//                ok = false;
            }
        }
        // Configure default manager if none was specified
        if (manager == null) {
        	if(logger.isDebugEnabled()) {
    			logger.debug("start - Configure default manager (as none was specified)" + getName());
    		}
        	
            if ((getCluster() != null) && getDistributable()) {
            	if(logger.isDebugEnabled()) {
        			logger.debug("start - try to create clustered manager" + getName());
        		}
                try {
                    manager = getCluster().createManager(getName());
                } catch (Exception ex) {
                    logger.error("standardContext.clusterFail", ex);
//                    ok = false;
                }
            } else {
                setManager(new SipStandardManager());
            }
        }

		//JSR 289 Section 2.1.1 Step 1.Deploy the application.
		//This will make start the sip context config, which will in turn parse the sip descriptor deployment
		//and call load on startup which is equivalent to
		//JSR 289 Section 2.1.1 Step 2.Invoke servlet.init(), the initialization method on the Servlet. Invoke the init() on all the load-on-startup Servlets in the applicatio
        if(manager instanceof DistributableSipManager) {
        	// due to refactoring on http://code.google.com/p/mobicents/issues/detail?id=2794
        	// we set the container on the manager right before the start and after the context have been init-ed
			hasDistributableManager = true;				
			if(logger.isDebugEnabled()) {
				logger.debug("this context contains a manager that allows applications to work in a distributed environment");
			}			
			((SipManager)getManager()).setMobicentsSipFactory(
					(sipApplicationDispatcher.getSipFactory()));
			((CatalinaSipManager)manager).setContainer(this);			
		}
		super.start();	
								
		if(getAvailable()) {			
			//set the session manager on the specific sipstandardmanager to handle converged http sessions
			if(!(getManager() instanceof DistributableSipManager) && getManager() instanceof CatalinaSipManager) {
				((SipManager)getManager()).setMobicentsSipFactory(
						(sipApplicationDispatcher.getSipFactory()));
				((CatalinaSipManager)manager).setContainer(this);	
			}
			// JSR 289 16.2 Servlet Selection
			// When using this mechanism (the main-servlet) for servlet selection, 
			// if there is only one servlet in the application then this
			// declaration is optional and the lone servlet becomes the main servlet
			if((mainServlet == null || mainServlet.length() < 1) && childrenMap.size() == 1) {
				setMainServlet(childrenMap.keySet().iterator().next());
			}
			sipSecurityUtils = new SipSecurityUtils(this);
			sipDigestAuthenticator = new DigestAuthenticator(sipApplicationDispatcher.getSipFactory().getHeaderFactory());
			//JSR 289 Section 2.1.1 Step 3.Invoke SipApplicationRouter.applicationDeployed() for this application.
			//called implicitly within sipApplicationDispatcher.addSipApplication
			sipApplicationDispatcher.addSipApplication(applicationName, this);			
			if(manager instanceof DistributableSipManager) {
				// only call the setContainer on the manager when it has been fully initialized
				hasDistributableManager = true;				
				if(logger.isDebugEnabled()) {
					logger.debug("this context contains a manager that allows applications to work in a distributed environment");
				}
				((SipManager)getManager()).setMobicentsSipFactory(
						(sipApplicationDispatcher.getSipFactory()));
				((CatalinaSipManager)manager).setContainer(this);	
			}
			if(logger.isDebugEnabled()) {
				logger.debug("sip application session timeout for this context is " + sipApplicationSessionTimeout + " minutes");
			}
			
			if(logger.isDebugEnabled()) {
				logger.debug("http session timeout for this context is " + getSessionTimeout() + " minutes");
			}
			if(logger.isDebugEnabled()) {
				logger.debug("sip context started " + getName());
			}
		} else {
			if(logger.isInfoEnabled()) {
				logger.info("sip context " + getName() + " didn't started due to errors");
			}
		}
										
	}

	@Override
	public ServletContext getServletContext() {	
        if (context == null) {
            context = new ConvergedApplicationContext(getBasePath(), this);
            if (getAltDDName() != null)
                context.setAttribute(Globals.ALT_DD_ATTR,getAltDDName());
        }

        return ((ConvergedApplicationContext)context).getFacade();

    }

	// AS7 invokes this before listenerStart()
	@Override
    public boolean contextListenerStart() {
		boolean ok = super.contextListenerStart();
		//the web listeners couldn't be started so we don't even try to load the sip ones
		if(!ok) {
			return ok;
		}

		if (logger.isDebugEnabled())
            logger.debug("Configuring sip listeners");

        // Instantiate the required listeners
        ClassLoader loader = getLoader().getClassLoader();
        ok = sipListeners.loadListeners(findSipApplicationListeners(), loader);
        if(!ok) {
			return ok;
		}

        List<ServletContextListener> servletContextListeners = sipListeners.getServletContextListeners();
        if (servletContextListeners != null) {
            ServletContextEvent event =
                new ServletContextEvent(getServletContext());
            for (ServletContextListener servletContextListener : servletContextListeners) {						
                if (servletContextListener == null)
                    continue;
                
                try {
                    fireContainerEvent("beforeContextInitialized", servletContextListener);
                    servletContextListener.contextInitialized(event);
                    fireContainerEvent("afterContextInitialized", servletContextListener);
                } catch (Throwable t) {
                    fireContainerEvent("afterContextInitialized", servletContextListener);
//                    getLogger().error
//                        (sm.getString("standardContext.listenerStart",
//                        		servletContextListener.getClass().getName()), t);
                    getLogger().error
                    	(MESSAGES.errorSendingContextInitializedEvent(servletContextListener.getClass().getName()), t);
                    ok = false;
                }
                
                // TODO Annotation processing                 
            }
        }

        return (ok);

    }

	@Override
	public boolean listenerStart() {
		boolean ok = super.listenerStart();
		//the web listeners couldn't be started so we don't even try to load the sip ones
		if(!ok) {
			return ok;
		}
		if (logger.isDebugEnabled())
            logger.debug("Configuring sip listeners");

        return ok;
	}

	@Override
	public boolean listenerStop() {
		boolean ok = super.listenerStop();
		if (logger.isDebugEnabled())
            logger.debug("Sending application stop events");
        
        List<ServletContextListener> servletContextListeners = sipListeners.getServletContextListeners();
        if (servletContextListeners != null) {
            ServletContextEvent event =
                new ServletContextEvent(getServletContext());
            for (ServletContextListener servletContextListener : servletContextListeners) {						
                if (servletContextListener == null)
                    continue;
                
                try {
                    fireContainerEvent("beforeContextDestroyed", servletContextListener);
                    servletContextListener.contextDestroyed(event);
                    fireContainerEvent("afterContextDestroyed", servletContextListener);
                } catch (Throwable t) {
                    fireContainerEvent("afterContextDestroyed", servletContextListener);
//                    getLogger().error
//                        (sm.getString("standardContext.listenerStop",
//                        		servletContextListener.getClass().getName()), t);
                    getLogger().error
                    	(MESSAGES.errorSendingContextDestroyedEvent(servletContextListener.getClass().getName()), t);                    
                    ok = false;
                }
                
                // TODO Annotation processing                 
            }
        }

        // TODO Annotation processing check super class on tomcat 6
        
        sipListeners.clean();

        return ok;
	}

	/**
     * Get base path. Copy pasted from StandardContext Tomcat class
     */
    public String getBasePath() {
        String docBase = null;
        Container container = this;
        while (container != null) {
            if (container instanceof Host)
                break;
            container = container.getParent();
        }
        File file = new File(getDocBase());
        if (!file.isAbsolute()) {
            if (container == null) {
                docBase = (new File(engineBase(), getDocBase())).getPath();
            } else {
                // Use the "appBase" property of this container
                String appBase = ((Host) container).getAppBase();
                file = new File(appBase);
                if (!file.isAbsolute())
                    file = new File(engineBase(), appBase);
                docBase = (new File(file, getDocBase())).getPath();
            }
        } else {
            docBase = file.getPath();
        }
        return docBase;
    }
	
	@Override
	public synchronized void stop() throws LifecycleException {
		if(logger.isInfoEnabled()) {
			logger.info("Stopping the sip context " + getName());
		}
		if(manager instanceof SipManager) {
			((SipManager)manager).dumpSipSessions();
			((SipManager)manager).dumpSipApplicationSessions();
			logger.warn("number of active sip sessions : " + ((SipManager)manager).getActiveSipSessions()); 
			logger.warn("number of active sip application sessions : " + ((SipManager)manager).getActiveSipApplicationSessions());
		}				
		super.stop();
		// this should happen after so that applications can still do some processing
		// in destroy methods to notify that context is getting destroyed and app removed
		sipListeners.deallocateServletsActingAsListeners();
		sipApplicationListeners.clear();
		sipServletMappings.clear();
		childrenMap.clear();
		childrenMapByClassName.clear();
		if(sipApplicationDispatcher != null) {
			if(applicationName != null) {
				sipApplicationDispatcher.removeSipApplication(applicationName);
			} else {
				logger.error("the application name is null for the following context : " + name);
			}
		}	
		if(sasTimerService != null && sasTimerService.isStarted()) {
			sasTimerService.stop();			
		}
		// Issue 1478 : nullify the ref to avoid reusing it
		sasTimerService = null;
		// Issue 1791 : don't check is the service is started it makes the stop
		// of tomcat hang
		if(timerService != null) {
			timerService.stop();
		}	
		if(proxyTimerService != null) {
			proxyTimerService.stop();
		}
		// Issue 1478 : nullify the ref to avoid reusing it
		timerService = null;
		getServletContext().setAttribute(javax.servlet.sip.SipServlet.TIMER_SERVICE, null);
		// Issue 48 (https://bitbucket.org/telestax/telscale-sip-servlets/issue/48/sipstandardservice-stopgracefuly-for)
		if(gracefulStopFuture != null) {
			gracefulStopFuture.cancel(false);
			gracefulStopFuture = null;
			if(logger.isDebugEnabled()) {
				logger.debug("context graceful task cancelled " + getName());
			}
		}
		if(logger.isInfoEnabled()) {
			logger.info("sip context stopped " + getName());
		}
	}

	@Override
	public void loadOnStartup(Container[] containers) {	
		super.loadOnStartup(containers);	
	}

	@Override
	public Wrapper createWrapper() {		
		return super.createWrapper();
	}		
	
	@Override
	public void addChild(Container container) {
		if(container instanceof Wrapper) {
			// Added for Issue http://code.google.com/p/mobicents/issues/detail?id=2382
			Wrapper wrapper = (Wrapper) container;
			SipServletImpl sipServletImpl = (SipServletImpl) childrenMap.get(container.getName());
			if(sipServletImpl != null && !sipServletImpl.getServletClass().equals(wrapper.getServletClass())) {
				throw new SipDeploymentException("Trying to add a servlet with name " + container.getName() + " and servlet class " + wrapper.getServletClass() + 
						" while there is already a SIP Servlet with the same name and a different servlet class " + sipServletImpl.getServletClass());
			}
		}
		if(children.get(container.getName()) == null) {
			super.addChild(container);
		} else {
			if(logger.isDebugEnabled()) {
				logger.debug(container.getName() + " already present as a Sip Servlet not adding it again");
			}
		}
	}
	
	public void addChild(SipServletImpl sipServletImpl) {
		SipServletImpl existingServlet = (SipServletImpl) childrenMap.get(sipServletImpl.getName());		
		if(existingServlet != null) {			
			logger.warn(sipServletImpl.getName() + " servlet already present, removing the previous one. " +
					"This might be due to the fact that the definition of the servlet " +
					"is present both in annotations and in sip.xml");
			//we remove the previous one (annoations) because it may not have init parameters that has been defined in sip.xml
			//See TCK Test ContextTest.testContext1
			childrenMap.remove(sipServletImpl.getName());
			childrenMapByClassName.remove(sipServletImpl.getServletClass());
			super.removeChild(existingServlet);
		}
		childrenMap.put(sipServletImpl.getName(), sipServletImpl);
		childrenMapByClassName.put(sipServletImpl.getServletClass(), sipServletImpl);
		super.addChild(sipServletImpl);		
	}
	
	public void removeChild(SipServletImpl sipServletImpl) {
		super.removeChild(sipServletImpl);
		childrenMap.remove(sipServletImpl.getName());
		childrenMapByClassName.remove(sipServletImpl.getServletClass());
	}
	
	/**
	 * {@inheritDoc}
	 */
	public Map<String, MobicentsSipServlet> getChildrenMap() {		
		return childrenMap;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public MobicentsSipServlet findSipServletByName(String name) {
		if (name == null)
            return (null);
		return childrenMap.get(name);
	}
	
	/**
	 * {@inheritDoc}
	 */
	public MobicentsSipServlet findSipServletByClassName(String className) {
		if (className == null)
            return (null);
		return childrenMapByClassName.get(className);
	}

	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#getApplicationName()
	 */
	public String getApplicationName() {
		return applicationName;
	}
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#getApplicationNameHashed()
	 */
	public String getApplicationNameHashed() {
		return sipApplicationDispatcher.getHashFromApplicationName(applicationName);
	}
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#setApplicationName(java.lang.String)
	 */
	public void setApplicationName(String applicationName) {
		this.applicationName = applicationName;
	}
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#getDescription()
	 */
	public String getDescription() {
		return description;
	}
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#setDescription(java.lang.String)
	 */
	public void setDescription(String description) {
		this.description = description;
	}
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#getLargeIcon()
	 */
	public String getLargeIcon() {
		return largeIcon;
	}
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#setLargeIcon(java.lang.String)
	 */
	public void setLargeIcon(String largeIcon) {
		this.largeIcon = largeIcon;
	}
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#getListeners()
	 */
	public SipListeners getListeners() {
		return sipListeners;
	}
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#setListeners(org.mobicents.servlet.sip.core.session.SipListenersHolder)
	 */
	public void setListeners(SipListeners listeners) {
		this.sipListeners = (CatalinaSipListenersHolder) listeners;
	}
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#isMainServlet()
	 */
	public boolean isMainServlet() {
		return isMainServlet;
	}
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#getMainServlet()
	 */
	public String getMainServlet() {
		return mainServlet;
	}
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#setMainServlet(java.lang.String)
	 */
	public void setMainServlet(String mainServlet) {
		this.mainServlet = mainServlet;
		servletHandler = mainServlet;
		this.isMainServlet = true;
	}

	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#getProxyTimeout()
	 */
	public int getProxyTimeout() {
		return proxyTimeout;
	}

	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#setProxyTimeout(int)
	 */
	public void setProxyTimeout(int proxyTimeout) {
		this.proxyTimeout = proxyTimeout;
	}
		
	public void addConstraint(SipSecurityConstraint securityConstraint) {		
		super.addConstraint(securityConstraint);
	}
	
	public void removeConstraint(SipSecurityConstraint securityConstraint) {
		super.removeConstraint(securityConstraint);
	}
	

	public void setSecurityDomain(String securityDomain) {
		this.securityDomain = securityDomain;
	}
	
	public String getSecurityDomain() {
		return securityDomain;
	}

	
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#getSmallIcon()
	 */
	public String getSmallIcon() {
		return smallIcon;
	}
	/* (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#setSmallIcon(java.lang.String)
	 */
	public void setSmallIcon(String smallIcon) {
		this.smallIcon = smallIcon;
	}
	
	@Override
	public void setLoginConfig(LoginConfig config) {	
		super.setLoginConfig(config);
	}
	
	@Override
	public LoginConfig getLoginConfig() {	
		return super.getLoginConfig();
	}
	
	public void setSipLoginConfig(MobicentsSipLoginConfig config) {
		this.sipLoginConfig = config;
	}
	
	public MobicentsSipLoginConfig getSipLoginConfig() {
		return this.sipLoginConfig;
	}
	/**
     * Add a new Listener class name to the set of Listeners
     * configured for this application.
     *
     * @param listener Java class name of a listener class
     */
    public void addSipApplicationListener(String listener) {
    	if(logger.isDebugEnabled()) {
			logger.debug("addSipApplicationListener " + getName());
		}
    	
    	sipApplicationListeners.add(listener);
    	fireContainerEvent("addSipApplicationListener", listener);

        // FIXME - add instance if already started?

    }
    
    /**
     * Remove the specified application listener class from the set of
     * listeners for this application.
     *
     * @param listener Java class name of the listener to be removed
     */
    public void removeSipApplicationListener(String listener) {

    	sipApplicationListeners.remove(listener);

        // Inform interested listeners
        fireContainerEvent("removeSipApplicationListener", listener);

        // FIXME - behavior if already started?

    }
    
    /**
     * Return the set of sip application listener class names configured
     * for this application.
     */
    public String[] findSipApplicationListeners() {
        return sipApplicationListeners.toArray(new String[sipApplicationListeners.size()]);
    }

	/**
	 * @return the sipApplicationDispatcher
	 */
	public SipApplicationDispatcher getSipApplicationDispatcher() {
		return sipApplicationDispatcher;
	}

	/**
	 * @return the sipFactoryFacade
	 */
	public SipFactoryFacade getSipFactoryFacade() {
		return sipFactoryFacade;
	}		
	
	/**
	 * @return the sipSessionsUtil
	 */
	public SipSessionsUtilImpl getSipSessionsUtil() {
		return sipSessionsUtil;
	}
	
	/**
	 * @return the timerService
	 */
	public TimerService getTimerService() {
		return timerService;
	}
	
	/**
	 * @return the proxyTimerService
	 */
	public ProxyTimerService getProxyTimerService() {
		return proxyTimerService;
	}
	
	/**
     * Get naming context full name.
     */
    private String getNamingContextName() {    	
	    if (namingContextName == null) {
			Container parent = getParent();
			if (parent == null) {
				namingContextName = getName();
			} else {
				Stack<String> stk = new Stack<String>();
				StringBuffer buff = new StringBuffer();
				while (parent != null) {
					stk.push(parent.getName());
					parent = parent.getParent();
				}
				while (!stk.empty()) {
					buff.append("/" + stk.pop());
				}
				buff.append(getName());
				namingContextName = buff.toString();
			}
		}
		return namingContextName;
    }
    
    @Override
    public synchronized void setManager(Manager manager) {
    	if(logger.isInfoEnabled()) {
			logger.info("setManager");
		}
    	if(getManager() != null && !manager.equals(getManager())) {
    		// http://code.google.com/p/mobicents/issues/detail?id=2794 : TimerService object from JDNI lookup or injection is never HATimerService. This means that timers scheduled using that object will not survive across cluster changes.
			// Avoid the DistributableSipManager set by TomcatConvergedDeployment to be overriden by JBossContextConfig.processContextConfig that reset the distributable manager.      		
    		if(logger.isInfoEnabled()) {
				logger.info("this context already contains a manager " + getManager() + " not setting new manager " + manager);
			}	
    		return;
    	}
    	if(manager instanceof CatalinaSipManager && sipApplicationDispatcher != null) {
			((SipManager)manager).setMobicentsSipFactory(
					sipApplicationDispatcher.getSipFactory()); 
			((CatalinaSipManager)manager).setContainer(this);
		}    	
    	super.setManager(manager);
    	if(logger.isInfoEnabled()) {
			logger.info("setManager - check if manager is distributable" + getName());
		}
    	if(manager instanceof DistributableSipManager) {
			hasDistributableManager = true;
			// if the logic comes unitl here, distributable is true, we set it to be able to start the FT timer services correctly on init
			setDistributable(true);
			if(logger.isInfoEnabled()) {
				logger.info("this context contains a manager that allows applications to work in a distributed environment" + getName());
			}			
		} 
    }
    
    @Override
    public Manager getManager() {    	
    	return super.getManager();
    }

	/**
	 * @return the sipApplicationSessionTimeout in minutes
	 */
	public int getSipApplicationSessionTimeout() {
		return sipApplicationSessionTimeout;
	}

	/**
	 * @param sipApplicationSessionTimeout the sipApplicationSessionTimeout to set in minutes
	 */
	public void setSipApplicationSessionTimeout(int sipApplicationSessionTimeout) {
		this.sipApplicationSessionTimeout = sipApplicationSessionTimeout;		
	}

	public Method getSipApplicationKeyMethod() {
		return sipApplicationKeyMethod;
	}

	public void setSipApplicationKeyMethod(Method sipApplicationKeyMethod) {
		this.sipApplicationKeyMethod = sipApplicationKeyMethod;
	}
	
	public String getJbossBasePath() {
		return getBasePath();
	}

	/**
	 * {@inheritDoc}
	 */
	public void addSipServletMapping(MobicentsSipServletMapping sipServletMapping) {
		sipServletMappings.add(sipServletMapping);
		isMainServlet = false;
		if(servletHandler == null) {
			servletHandler = sipServletMapping.getServletName();
		}
	}
	/**
	 * {@inheritDoc}
	 */
	public List<MobicentsSipServletMapping> findSipServletMappings() {
		return sipServletMappings;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public MobicentsSipServletMapping findSipServletMappings(SipServletRequest sipServletRequest) {
		if(logger.isDebugEnabled()) {
			logger.debug("Checking sip Servlet Mapping for following request : " + sipServletRequest);
		}
		for (MobicentsSipServletMapping sipServletMapping : sipServletMappings) {
			if(sipServletMapping.getMatchingRule().matches(sipServletRequest)) {
				return sipServletMapping;
			} else {
				logger.debug("Following mapping rule didn't match : servletName => " + 
						sipServletMapping.getServletName() + " | expression = "+ 
						sipServletMapping.getMatchingRule().getExpression());
			}
		}
		return null;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public void removeSipServletMapping(MobicentsSipServletMapping sipServletMapping) {
		sipServletMappings.remove(sipServletMapping);
	}

	/**
	 * {@inheritDoc}
	 */
	public final SipManager getSipManager() {
		return (SipManager)manager;
	}
	
	@Override
	public String getInfo() {
		return info;
	}

//	public AnnotationProcessor getAnnotationProcessor() {
//		// TODO Auto-generated method stub
//		return new DummyAnnotationProcessor();
//	}
	
// ----------------------------------------------- DummyAnnotationProcessor Inner Class
    
    
//    protected class DummyAnnotationProcessor implements AnnotationProcessor {
//
//        public void postConstruct(Object instance)
//                throws IllegalAccessException, InvocationTargetException {
//            // Do nothing
//        }
//
//        public void preDestroy(Object instance) throws IllegalAccessException,
//                InvocationTargetException {
//            getInstanceManager().destroyInstance(instance);
//        }
//
//        public void processAnnotations(Object instance)
//                throws IllegalAccessException, InvocationTargetException,
//                NamingException {
//            getInstanceManager().newInstance(instance);
//        }
//        
//    }

    /*
     * (non-Javadoc)
     * @see org.mobicents.servlet.sip.core.SipContext#enterSipApp(org.mobicents.servlet.sip.core.session.MobicentsSipApplicationSession, org.mobicents.servlet.sip.core.session.MobicentsSipSession, boolean, boolean)
     */
    public void enterSipApp(MobicentsSipApplicationSession sipApplicationSession, MobicentsSipSession sipSession, boolean checkIsManagedThread, boolean isContainerManaged) {
    	if(logger.isDebugEnabled()) {
			logger.debug("enterSipApp " + getName());
		}
		switch (concurrencyControlMode) {
			case SipSession:				
				if(sipSession != null) {
					sipSession.acquire();					
				} 
				break;
			case SipApplicationSession:
				if(logger.isDebugEnabled()) {
					logger.debug("checkIsManagedThread " + checkIsManagedThread + " , isManagedThread " + isManagedThread.get() + ", isContainerManaged " + isContainerManaged);
				}
				// http://code.google.com/p/mobicents/issues/detail?id=2534 && http://code.google.com/p/mobicents/issues/detail?id=2526 
				if(!checkIsManagedThread || (checkIsManagedThread && Boolean.TRUE.equals(isManagedThread.get()))) {
					if(isManagedThread.get() == null) {
						isManagedThread.set(Boolean.TRUE);
					}
					if(sipApplicationSession != null) {									
						SipApplicationSessionCreationThreadLocal sipApplicationSessionCreationThreadLocal = SipApplicationSessionCreationThreadLocal.getTHRef().get();
						if(sipApplicationSessionCreationThreadLocal == null) {
							sipApplicationSessionCreationThreadLocal = new SipApplicationSessionCreationThreadLocal();
							SipApplicationSessionCreationThreadLocal.getTHRef().set(sipApplicationSessionCreationThreadLocal);
						}
						boolean notPresent = sipApplicationSessionCreationThreadLocal.getSipApplicationSessions().add(sipApplicationSession);
						if(notPresent && isContainerManaged) {
							if(logger.isDebugEnabled()) {
								logger.debug("acquiring sipApplicationSession=" + sipApplicationSession +
										" since it is not present in our local thread of accessed sip application sessions " );
							}
							sipApplicationSession.acquire();
						} else if(logger.isDebugEnabled()) {
							if(!isContainerManaged) {
								logger.debug("not acquiring sipApplicationSession=" + sipApplicationSession +
										" since application specified the container shouldn't manage it ");
							} else {
								logger.debug("not acquiring sipApplicationSession=" + sipApplicationSession +
										" since it is present in our local thread of accessed sip application sessions ");
							}
						}
					}
				} else {
					if(logger.isDebugEnabled()) {
						logger.debug("not acquiring sipApplicationSession=" + sipApplicationSession +
								" since isManagedThread is " + isManagedThread.get());
					}
				}
				break;
			case None:
				break;
		}		
	} 
    
    /*
     * (non-Javadoc)
     * @see org.mobicents.servlet.sip.startup.SipContext#exitSipApp(org.mobicents.servlet.sip.core.session.MobicentsSipApplicationSession, org.mobicents.servlet.sip.core.session.MobicentsSipSession)
     */
	public void exitSipApp(MobicentsSipApplicationSession sipApplicationSession, MobicentsSipSession sipSession) {
		if(logger.isDebugEnabled()) {
			logger.debug("exitSipApp " + getName());
		}
		switch (concurrencyControlMode) {
			case SipSession:
				if(sipSession != null) {
					sipSession.release();
				} else {
					if(logger.isDebugEnabled()) {
						logger.debug("NOT RELEASING SipSession on exit sipApplicationSession=" + sipApplicationSession +
								" sipSession=" + sipSession + " semaphore=null");
					}
				}
				break;
			case SipApplicationSession:
				boolean wasSessionReleased = false;
				SipApplicationSessionCreationThreadLocal sipApplicationSessionCreationThreadLocal = SipApplicationSessionCreationThreadLocal.getTHRef().get();
				if(sipApplicationSessionCreationThreadLocal != null) {					
					for(MobicentsSipApplicationSession sipApplicationSessionAccessed : SipApplicationSessionCreationThreadLocal.getTHRef().get().getSipApplicationSessions()) {
						sipApplicationSessionAccessed.release();
						if(sipApplicationSessionAccessed.equals(sipApplicationSession)) {
							wasSessionReleased = true;
						}
					}		
					SipApplicationSessionCreationThreadLocal.getTHRef().get().getSipApplicationSessions().clear();
					SipApplicationSessionCreationThreadLocal.getTHRef().set(null);
					SipApplicationSessionCreationThreadLocal.getTHRef().remove();
				}
				isManagedThread.set(null);
				isManagedThread.remove();
				if(!wasSessionReleased) {
					if(sipApplicationSession != null) {
						sipApplicationSession.release();
					} else {
						if(logger.isDebugEnabled()) {
							logger.debug("NOT RELEASING SipApplicationSession on exit sipApplicationSession=" + sipApplicationSession +
									" sipSession=" + sipSession + " semaphore=null");
						}
					}
				}
				break;
			case None:
				break;
		}		
	}   
    
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.startup.SipContext#enterSipAppHa(boolean)
	 */
    public boolean enterSipAppHa(boolean startCacheActivity) {
    	if(logger.isDebugEnabled()) {
			logger.debug("enterSipAppHa " + getName());
		}
    	boolean batchStarted = false;
// FIXME: distributable not supported
		if(getDistributable() && hasDistributableManager) {
			if(logger.isDebugEnabled()) {
				logger.debug("enterSipAppHa - distributable, starting batch - " + getName());
			}
			batchStarted = startBatchTransaction();
			if(logger.isDebugEnabled()) {
				logger.debug("enterSipAppHa - batchStarted=" + batchStarted + " - " + getName());
			}
//			if(bindSessions) {
//				ConvergedSessionReplicationContext.enterSipappAndBindSessions(sipApplicationSession,
//				getSipManager(), startCacheActivity);
//			} else {
				ConvergedSessionReplicationContext.enterSipapp(null, null, startCacheActivity);
//			}
		}
// FIXME - VEGE: distributable not supported
		return batchStarted;
	}
	
    /*
     * (non-Javadoc)
     * @see org.mobicents.servlet.sip.startup.SipContext#exitSipAppHa(org.mobicents.servlet.sip.message.SipServletRequestImpl, org.mobicents.servlet.sip.message.SipServletResponseImpl, boolean)
     */
	public void exitSipAppHa(MobicentsSipServletRequest request, MobicentsSipServletResponse response, boolean batchStarted) {
		if(logger.isDebugEnabled()) {
			logger.debug("exitSipAppHa " + getName());
		}
// FIXME: distributable not supported
		if (getDistributable() && hasDistributableManager) {
			if(logger.isInfoEnabled()) {
				if(request != null) {
					logger.info("We are now after the servlet invocation for request " + request + ", We replicate no matter what " );
				} else if (response != null) {
					logger.info("We are now after the servlet invocation for request " + response + ", We replicate no matter what " );
				} else {
					logger.info("We are now after the servlet invocation, We replicate no matter what " );
				}
			}
			try {
				ConvergedSessionReplicationContext ctx = ConvergedSessionReplicationContext
						.exitSipapp();

				final SnapshotSipManager snapshotSipManager = ctx.getSoleSnapshotSipManager();
				if(logger.isDebugEnabled()) {
					logger.debug("exitSipAppHa - Snapshot Manager " + snapshotSipManager);
				}
				if (snapshotSipManager != null) {
					Set<ClusteredSipSession<? extends OutgoingDistributableSessionData>> sipSessions = ctx.getSipSessions();
					for (ClusteredSipSession<? extends OutgoingDistributableSessionData> clusteredSipSession : sipSessions) {
						if(logger.isDebugEnabled()) {
							logger.debug("exitSipAppHa - calling snapshot for clustered sip session with id " + clusteredSipSession.getId() + "(" +clusteredSipSession.getHaId()+ ")");
						}
						snapshotSipManager.snapshot(clusteredSipSession);
					}
					Set<ClusteredSipApplicationSession<? extends OutgoingDistributableSessionData>> sipApplicationSessions = ctx.getSipApplicationSessions();
					for (ClusteredSipApplicationSession<? extends OutgoingDistributableSessionData> clusteredSipApplicationSession : sipApplicationSessions) {
						if(logger.isDebugEnabled()) {
							logger.debug("exitSipAppHa - calling snapshot for clustered sip app session with id (HaId): " + clusteredSipApplicationSession.getId() + "(" +clusteredSipApplicationSession.getHaId()+ ")");
						}
						snapshotSipManager.snapshot(clusteredSipApplicationSession);
					}
				} 
			} catch (Throwable e) {
				logger.error("A problem occured while replicating", e);
				// no need to rethrow an exception here as this is not recoverable and this could mess up the concurrency release of the semaphore on the session
			} finally {
				if(logger.isDebugEnabled()) {
					logger.debug("exitSipAppHa - calling endBatchTransaction");
				}
				endBatchTransaction(batchStarted);
				if(logger.isDebugEnabled()) {
					if(request != null) {
						logger.debug("We are now after the replication finishCacheActivity for request " + request + ", We replicate no matter what " );
					} else if (response != null) {
						logger.debug("We are now after the replication finishCacheActivity for request " + response + ", We replicate no matter what " );
					} else {
						logger.debug("We are now after the replication finishCacheActivity, We replicate no matter what " );
					}
				}
			}
		}
// FIXME - VEGE: distributable not supported
	}
	
// FIXME: distributable not supported
	private boolean startBatchTransaction() {
		if(logger.isDebugEnabled()) {
			logger.debug("startBatchTransaction " + getName());
		}
		DistributedCacheConvergedSipManager distributedConvergedManager = ((ClusteredSipSessionManager) manager)
				.getDistributedCacheConvergedSipManager();
		BatchingManager tm = distributedConvergedManager.getBatchingManager();
		boolean started = false;
		try {
			if (tm != null && tm.isBatchInProgress() == false) {
				tm.startBatch();
				started = true;
			}
		} catch (RuntimeException re) {
			throw re;
		} catch (Exception e) {
			throw new IllegalStateException(
					"Failed to initiate batch replication transaction", e);
		}

		return started;
	}
// FIXME - VEGE: distributable not supported
	
// FIXME: distributable not supported
	private void endBatchTransaction(boolean wasStarted) {
		if(logger.isDebugEnabled()) {
			logger.debug("endBatchTransaction - wasStarted=" + wasStarted + " " + getName());
		}
		
		DistributedCacheConvergedSipManager<? extends OutgoingDistributableSessionData> distributedConvergedManager = ((ClusteredSipSessionManager) manager)
				.getDistributedCacheConvergedSipManager();
		
		if(logger.isDebugEnabled()) {
			logger.debug("endBatchTransaction - distributedConvergedManager=" + distributedConvergedManager);
			logger.debug("endBatchTransaction - distributedConvergedManager.getClass().getCanonicalName()=" + distributedConvergedManager.getClass().getCanonicalName());
			logger.debug("endBatchTransaction - distributedConvergedManager.getInfinispanCache().getName()=" + distributedConvergedManager.getInfinispanCache().getName());
			logger.debug("endBatchTransaction - get batching manager...");
		}
		
		BatchingManager tm = distributedConvergedManager.getBatchingManager();
		
		if(logger.isDebugEnabled()) {
			logger.debug("endBatchTransaction - batching manager resolved=" + tm);
		}
		
		try {
			if (tm != null && tm.isBatchInProgress() == true && wasStarted) {
				if(logger.isDebugEnabled()) {
					logger.debug("endBatchTransaction - endBatch");
				}
				tm.endBatch();
			}
		} catch (Exception e) {
			logger.error("Failed to stop batch replication transaction", e);
			// no need to rethrow an exception here as this is not recoverable and this could mess up the concurrency release of the semaphore on the session
		}
	}
// FIXME - VEGE: distributable not supported
	
	public boolean notifySipContextListeners(SipContextEvent event) {
		if(logger.isDebugEnabled()) {
			logger.debug("notifySipContextListeners " + getName());
		}
		
		boolean ok = true;
		if(logger.isDebugEnabled()) {
			logger.debug(childrenMap.size() + " container to notify of " + event.getEventType());
		}
		if(event.getEventType() == SipContextEventType.SERVLET_INITIALIZED) {
                        //fixes https://github.com/RestComm/sip-servlets/issues/165
                        //now the SipService is totally ready/started, we prepare 
                        //the context again just in case some att was not properly
                        //initiated
                        try {
                            prepareServletContext();
                        } catch (Exception e) {
                            logger.warn("Couldnt prepare context", e);
                        }                    
			if(!timerService.isStarted()) {
				// ###TIMER
				//timerService.start();
			}
			if(!proxyTimerService.isStarted()) {
				proxyTimerService.start();
			}
			if(!sasTimerService.isStarted()) {
				// ###TIMER
				//sasTimerService.start();
			}
		}
		
		if(this.available) {
			if(logger.isDebugEnabled()) {
				logger.debug("notifySipContextListeners - available " + getName());
			}
			
			enterSipApp(null, null, false, true);
			boolean batchStarted = enterSipAppHa(true);
			// https://github.com/Mobicents/sip-servlets/issues/52
			List<MobicentsSipServlet> sipServlets = new ArrayList<MobicentsSipServlet>(childrenMap.values());
			Collections.sort(sipServlets, new SipServletLoadOnStartupComparator());
			
			try {
				for (MobicentsSipServlet container : sipServlets) {
					if(logger.isDebugEnabled()) {
						logger.debug("container " + container.getName() + ", class : " + container.getClass().getName() + ", load-on-startup : " + container.getLoadOnStartup());
					}
					if(container instanceof Wrapper) {			
						Wrapper wrapper = (Wrapper) container;
						Servlet sipServlet = null;
						try {
							sipServlet = wrapper.allocate();
							if(sipServlet instanceof SipServlet) {
								// Fix for issue 1086 (http://code.google.com/p/mobicents/issues/detail?id=1086) : 
								// Cannot send a request in SipServletListener.initialize() for servlet-selection applications
								boolean servletHandlerWasNull = false;
								if(servletHandler == null) {
									servletHandler = container.getName();
									servletHandlerWasNull = true;
								}
								final ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
								try {
									final ClassLoader cl = getLoader().getClassLoader();
									Thread.currentThread().setContextClassLoader(cl);
									// http://code.google.com/p/sipservlets/issues/detail?id=135
									bindThreadBindingListener();
								
									switch(event.getEventType()) {
										case SERVLET_INITIALIZED : {
											SipServletContextEvent sipServletContextEvent = 
												new SipServletContextEvent(getServletContext(), (SipServlet)sipServlet);
											List<SipServletListener> sipServletListeners = sipListeners.getSipServletsListeners();
											if(logger.isDebugEnabled()) {
												logger.debug(sipServletListeners.size() + " SipServletListener to notify of servlet initialization");
											}
											for (SipServletListener sipServletListener : sipServletListeners) {					
												sipServletListener.servletInitialized(sipServletContextEvent);					
											}
											break;
										}
										case SIP_CONNECTOR_ADDED : {
											// reload the outbound interfaces if they have changed
											this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.OUTBOUND_INTERFACES,
													sipApplicationDispatcher.getOutboundInterfaces());	
											// https://code.google.com/p/sipservlets/issues/detail?id=246
	                                        this.getServletContext().setAttribute("org.mobicents.servlet.sip.SIP_CONNECTORS",
	                                                sipApplicationDispatcher.getSipService().findSipConnectors());
											
											List<SipConnectorListener> sipConnectorListeners = sipListeners.getSipConnectorListeners();
											if(logger.isDebugEnabled()) {
												logger.debug(sipConnectorListeners.size() + " SipConnectorListener to notify of sip connector addition");
											}
											for (SipConnectorListener sipConnectorListener : sipConnectorListeners) {					
												sipConnectorListener.sipConnectorAdded((SipConnector)event.getEventObject());				
											}
											break;
										}
										case SIP_CONNECTOR_REMOVED : {
											// reload the outbound interfaces if they have changed
											this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.OUTBOUND_INTERFACES,
													sipApplicationDispatcher.getOutboundInterfaces());
											// https://code.google.com/p/sipservlets/issues/detail?id=246
	                                        this.getServletContext().setAttribute("org.mobicents.servlet.sip.SIP_CONNECTORS",
	                                                sipApplicationDispatcher.getSipService().findSipConnectors());
											
											List<SipConnectorListener> sipConnectorListeners = sipListeners.getSipConnectorListeners();
											if(logger.isDebugEnabled()) {
												logger.debug(sipConnectorListeners.size() + " SipConnectorListener to notify of sip connector removal");
											}
											for (SipConnectorListener sipConnectorListener : sipConnectorListeners) {					
												sipConnectorListener.sipConnectorRemoved((SipConnector)event.getEventObject());				
											}
											break;
										}								
									}
									if(servletHandlerWasNull) {
										servletHandler = null;
									}
								} finally {
									// http://code.google.com/p/sipservlets/issues/detail?id=135
									unbindThreadBindingListener();
									Thread.currentThread().setContextClassLoader(oldClassLoader);
								}
							}					
						} catch (ServletException e) {
							logger.error("Cannot allocate the servlet "+ wrapper.getServletClass() +" for notifying the listener " +
									" of the event " + event.getEventType(), e);
							ok = false; 
						} catch (Throwable e) {
							logger.error("An error occured when notifying the servlet " + wrapper.getServletClass() +
									" of the event " + event.getEventType(), e);
							ok = false; 
						} 
						try {
							if(sipServlet != null) {
								wrapper.deallocate(sipServlet);
							}
						} catch (ServletException e) {
				            logger.error("Deallocate exception for servlet" + wrapper.getName(), e);
				            ok = false;
						} catch (Throwable e) {
							logger.error("Deallocate exception for servlet" + wrapper.getName(), e);
				            ok = false;
						}
					}
				}
			} finally {
				exitSipAppHa(null, null, batchStarted);
				exitSipApp(null, null);	
			}
		}
		return ok;
	}

	public ConcurrencyControlMode getConcurrencyControlMode() {		
		return concurrencyControlMode;
	}

	public void setConcurrencyControlMode(ConcurrencyControlMode mode) {
		this.concurrencyControlMode = mode;
		if(concurrencyControlMode != null && logger.isDebugEnabled()) {
			logger.debug("Concurrency Control set to " + concurrencyControlMode.toString() + " for application " + applicationName);
		}
	}
	
	public SipRubyController getSipRubyController() {
		return rubyController;
	}

	public void setSipRubyController(SipRubyController sipRubyController) {
		this.rubyController = sipRubyController;
	}
	
	public SipApplicationSessionTimerService getSipApplicationSessionTimerService() {
		return sasTimerService;
	}	

	/**
	 * @return the hasDistributableManager
	 */
	public boolean hasDistributableManager() {
		return hasDistributableManager;
	}
	
	/**
	 * @return the servletHandler
	 */
	public String getServletHandler() {
		return servletHandler;
	}

	/**
	 * @param servletHandler the servletHandler to set
	 */
	public void setServletHandler(String servletHandler) {
		this.servletHandler = servletHandler;
	}
	
	public boolean isPackageProtectionEnabled() {		
		return SecurityUtil.isPackageProtectionEnabled();
	}
	
	public ClassLoader getSipContextClassLoader() {
		return getLoader().getClassLoader();
	}

	public boolean authorize(MobicentsSipServletRequest request) {
		return sipSecurityUtils.authorize(request);
	}

	public SipDigestAuthenticator getDigestAuthenticator() {
		return sipDigestAuthenticator;
	}

	public SipInstanceManager getSipInstanceManager() {
    	return (SipInstanceManager) super.getInstanceManager();
    }
	
    @Override
    public void setInstanceManager(InstanceManager instanceManager) {
        super.setInstanceManager(instanceManager);
    }

	// http://code.google.com/p/sipservlets/issues/detail?id=135
	public void bindThreadBindingListener() {
		super.getThreadBindingListener().bind();
	}
	public void unbindThreadBindingListener() {
		super.getThreadBindingListener().unbind();
	}

	@Override
	public void enterSipContext() {
		final ClassLoader cl = getSipContextClassLoader();
		Thread.currentThread().setContextClassLoader(cl);
		// http://code.google.com/p/sipservlets/issues/detail?id=135
		bindThreadBindingListener();
	}

	@Override
	public void exitSipContext(ClassLoader oldClassLoader) {
		// http://code.google.com/p/sipservlets/issues/detail?id=135
		unbindThreadBindingListener();
		Thread.currentThread().setContextClassLoader(oldClassLoader);
	}


	@Override
	public void stopGracefully(long timeToWait) {
		// http://code.google.com/p/sipservlets/issues/detail?id=195 
		// Support for Graceful Shutdown of SIP Applications and Overall Server
		if(logger.isInfoEnabled()) {
			logger.info("Stopping the Context " + getName() + " Gracefully in " + timeToWait + " ms");
		}
		// Guarantees that the application won't be routed any initial requests anymore but will still handle subsequent requests
		List<String> applicationsUndeployed = new ArrayList<String>();
		applicationsUndeployed.add(applicationName);
		sipApplicationDispatcher.getSipApplicationRouter().applicationUndeployed(applicationsUndeployed);
		if(timeToWait == 0) {
			// equivalent to forceful stop
			if(gracefulStopFuture != null) {
				gracefulStopFuture.cancel(false);
			}
			try {
				stop();
			} catch (LifecycleException e) {
				logger.error("The server couldn't be stopped", e);
			}
		} else { 
			long gracefulStopTaskInterval = 30000;
			if(timeToWait > 0 && timeToWait < gracefulStopTaskInterval) {
				// if the time to Wait is positive and < to the gracefulStopTaskInterval then we schedule the task directly once to the time to wait
				gracefulStopFuture = sipApplicationDispatcher.getAsynchronousScheduledExecutor().schedule(new ContextGracefulStopTask(this, timeToWait), timeToWait, TimeUnit.MILLISECONDS);         
			} else {
				// if the time to Wait is > to the gracefulStopTaskInterval or infinite (negative value) then we schedule the task to run every gracefulStopTaskInterval, not needed to be exactly precise on the timeToWait in this case
				gracefulStopFuture = sipApplicationDispatcher.getAsynchronousScheduledExecutor().scheduleWithFixedDelay(new ContextGracefulStopTask(this, timeToWait), gracefulStopTaskInterval, gracefulStopTaskInterval, TimeUnit.MILLISECONDS);                      
			}
		}		
	}

	@Override
	public boolean isStoppingGracefully() {
		if(gracefulStopFuture != null)
			return true;
		return false;
	}
	
	// https://github.com/Mobicents/sip-servlets/issues/52
	protected class SipServletLoadOnStartupComparator implements Comparator<MobicentsSipServlet> {

		@Override
		public int compare(MobicentsSipServlet o1, MobicentsSipServlet o2) {
			if(o1 != null && o2 != null) {
				if(o1.getLoadOnStartup() > o2.getLoadOnStartup()) {
					return 1;
				} else if(o1.getLoadOnStartup() == o2.getLoadOnStartup()) {
					return 0;
				} else {
					return -1;
				}
			}
			return 0;
		}
	}
}

