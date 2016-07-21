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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
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
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Loader;
import org.apache.catalina.Manager;
import org.apache.catalina.Service;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.NamingContextListener;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.security.SecurityUtil;
import org.apache.catalina.util.ContextName;
import org.apache.catalina.util.ExtensionValidator;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.log4j.Logger;
/*import org.apache.naming.resources.FileDirContext;
import org.apache.naming.resources.WARDirContext;*/
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.util.descriptor.web.Injectable;
import org.apache.tomcat.util.descriptor.web.InjectionTarget;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.mobicents.servlet.sip.SipConnector;
import org.mobicents.servlet.sip.annotation.ConcurrencyControlMode;
import org.mobicents.servlet.sip.annotations.DefaultSipInstanceManager;
import org.mobicents.servlet.sip.catalina.CatalinaSipContext;
import org.mobicents.servlet.sip.catalina.CatalinaSipListenersHolder;
import org.mobicents.servlet.sip.catalina.CatalinaSipManager;
import org.mobicents.servlet.sip.catalina.ContextGracefulStopTask;
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
	private static transient final Logger logger = Logger.getLogger(SipStandardContext.class);

	/**
	 * The descriptive information string for this implementation.
	 */
	private static final String info =
			"org.mobicents.servlet.sip.startup.SipStandardContext/1.0";

	// as mentionned per JSR 289 Section 6.1.2.1 default lifetime for an 
	// application session is 3 minutes
	private static int DEFAULT_LIFETIME = 3;

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
	private boolean isMainServlet;
	private String mainServlet;
	/**
	 * The set of sip servlet mapping configured for this
	 * application.
	 */
	protected transient List<MobicentsSipServletMapping> sipServletMappings = new ArrayList<MobicentsSipServletMapping>();

	protected transient SipApplicationDispatcher sipApplicationDispatcher = null;

	protected transient Map<String, MobicentsSipServlet> childrenMap;
	protected transient Map<String, MobicentsSipServlet> childrenMapByClassName;

	protected boolean sipJNDIContextLoaded = false;

	// timer service used to schedule sip application session expiration timer
	protected transient SipApplicationSessionTimerService sasTimerService = null;
	// timer service used to schedule sip servlet originated timer tasks
	protected transient SipServletTimerService timerService = null;
	// timer service used to schedule proxy timer tasks
	protected transient ProxyTimerService proxyTimerService = null;
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
		hasDistributableManager = false;
	}

	@Override
	public void initInternal() throws LifecycleException {
		if(logger.isInfoEnabled()) {
			logger.info("Initializing the sip context " + getName());
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
		super.initInternal();

		prepareServletContext();

		if(logger.isInfoEnabled()) {
			logger.info("sip context Initialized " + getName());
		}	
	}

	protected void prepareServletContext() throws LifecycleException {
		if(sipApplicationDispatcher == null) {
			setApplicationDispatcher();
		}
		if(sipFactoryFacade == null) {
			sipFactoryFacade = new SipFactoryFacade((SipFactoryImpl)sipApplicationDispatcher.getSipFactory(), this);
		}
		if(sipSessionsUtil == null) {
			sipSessionsUtil = new SipSessionsUtilImpl(this);
		}
		if(timerService == null) {			
			timerService = new TimerServiceImpl(sipApplicationDispatcher.getSipService(), applicationName);			
		}
		if(proxyTimerService == null) {
			String proxyTimerServiceType = sipApplicationDispatcher.getSipService().getProxyTimerServiceImplementationType();
			if(proxyTimerServiceType != null && proxyTimerServiceType.equalsIgnoreCase("Standard")) {
                proxyTimerService = new ProxyTimerServiceImpl(applicationName);
            } else if(proxyTimerServiceType != null && proxyTimerServiceType.equalsIgnoreCase("Default")) {
                proxyTimerService = new DefaultProxyTimerService(applicationName);
            } else {
                proxyTimerService = new ProxyTimerServiceImpl(applicationName);
            }
		}

		if(sasTimerService == null || !sasTimerService.isStarted()) {
			String sasTimerServiceType = sipApplicationDispatcher.getSipService().getSasTimerServiceImplementationType();
			if(sasTimerServiceType != null && sasTimerServiceType.equalsIgnoreCase("Standard")) {
                sasTimerService = new StandardSipApplicationSessionTimerService(applicationName);
            } else if (sasTimerServiceType != null && sasTimerServiceType.equalsIgnoreCase("Default")) {
                sasTimerService = new DefaultSipApplicationSessionTimerService(applicationName);
            } else {
                sasTimerService = new StandardSipApplicationSessionTimerService(applicationName);
            }
		}
		//needed when restarting applications through the tomcat manager 
		this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.SIP_FACTORY,
				sipFactoryFacade);		
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
	public synchronized void startInternal() throws LifecycleException {
		if(logger.isInfoEnabled()) {
			logger.info("Starting the sip context " + getName());
		}
		//		if( this.getState().equals(LifecycleState.INITIALIZED)) { 
		prepareServletContext();
		//		}	
		// Add missing components as necessary
		boolean ok = true;
		// Currently this is effectively a NO-OP but needs to be called to
        // ensure the NamingResources follows the correct lifecycle
        if (getNamingResources() != null) {
        	getNamingResources().start();
        }

        // Add missing components as necessary
        if (getResources() == null) {   // (1) Required by Loader
            if (logger.isDebugEnabled())
                logger.debug("Configuring default Resources");

            try {
                setResources(new StandardRoot(this));
            } catch (IllegalArgumentException e) {
                logger.error("Error initializing resources: " + e.getMessage());
                ok = false;
            }
        }
        if (ok) {
            resourcesStart();
        }

        if (getLoader() == null) {
            WebappLoader webappLoader = new WebappLoader(getParentClassLoader());
            webappLoader.setDelegate(getDelegate());
            setLoader(webappLoader);
        }

        // Initialize character set mapper
        getCharsetMapper();

        // Post work directory
        postWorkDirectory();

        // Validate required extensions
        boolean dependencyCheck = true;
        try {
            dependencyCheck = ExtensionValidator.validateApplication
                (getResources(), this);
        } catch (IOException ioe) {
            logger.error("Error in dependencyCheck", ioe);
            dependencyCheck = false;
        }

        if (!dependencyCheck) {
            // do not make application available if depency check fails
            ok = false;
        }
		
		// Reading the "catalina.useNaming" environment variable
		String useNamingProperty = System.getProperty("catalina.useNaming");
		if ((useNamingProperty != null)
				&& (useNamingProperty.equals("false"))) {
			setUseNaming(false);
		}

		// Standard container startup
        if (logger.isDebugEnabled())
            logger.debug("Processing standard container startup");

        Loader loader = getLoader();
        if ((loader != null) && (loader instanceof Lifecycle)) {
        	// we start the loader before we create the sip instance manager otherwise the classloader is null 
            ((Lifecycle) loader).start();
        }
        
		if (ok && isUseNaming()) {
            if (getNamingContextListener() == null) {
                NamingContextListener namingContextListener = new SipNamingContextListener();
                namingContextListener.setName(getNamingContextName());
                namingContextListener.setExceptionOnFailedWrite(getJndiExceptionOnFailedWrite());
                setNamingContextListener(namingContextListener);
                addLifecycleListener(namingContextListener);
                addContainerListener(namingContextListener);  
            }
            // Replace the default annotation processor. This is needed to handle resource injection
			// for SipFactory, Session utils and other objects residing in the servlet context space.
			// Of course if the variable is not found in in the servet context it defaults to the
			// normal lookup method - in the default naming context.
			//tomcat naming 
			Map<String, Map<String, String>> injectionMap = buildInjectionMap(
					getIgnoreAnnotations() ? new NamingResourcesImpl(): getNamingResources());
			this.setInstanceManager(
					new DefaultSipInstanceManager(
							getNamingContextListener().getEnvContext(),
							injectionMap, this, this.getLoader().getClassLoader()));
        }
		
        // Acquire clustered manager
        Manager contextManager = null;
        Manager manager = getManager();
        if (manager == null) {
            if (logger.isDebugEnabled()) {
                logger.debug(sm.getString("standardContext.cluster.noManager",
                        Boolean.valueOf((getCluster() != null)),
                        Boolean.valueOf(getDistributable())));
            }
            if ( (getCluster() != null) && getDistributable()) {
                try {
                    contextManager = getCluster().createManager(getName());
                } catch (Exception ex) {
                    logger.error("standardContext.clusterFail", ex);
                    ok = false;
                }
            } else {
                contextManager = new SipStandardManager();
            }
        }

        // Configure default manager if none was specified
        if (contextManager != null) {
            if (logger.isDebugEnabled()) {
                logger.debug(sm.getString("standardContext.manager",
                        contextManager.getClass().getName()));
            }
            setManager(contextManager);
            manager = getManager();
        }

        if (manager!=null && (getCluster() != null) && getDistributable()) {
            //let the cluster know that there is a context that is distributable
            //and that it has its own manager
            getCluster().registerManager(manager);
        }
        
		
		//JSR 289 Section 2.1.1 Step 1.Deploy the application.
		//This will make start the sip context config, which will in turn parse the sip descriptor deployment
		//and call load on startup which is equivalent to
		//JSR 289 Section 2.1.1 Step 2.Invoke servlet.init(), the initialization method on the Servlet. Invoke the init() on all the load-on-startup Servlets in the application
		super.startInternal();	

		if(getState().isAvailable()) {
			// Replace the default annotation processor. This is needed to handle resource injection
			// for SipFactory, Session utils and other objects residing in the servlet context space.
			// Of course if the variable is not found in in the servet context it defaults to the
			// normal lookup method - in the default naming context.
			if(getInstanceManager() == null || !(getInstanceManager() instanceof SipInstanceManager)) {
				if(isUseNaming()) {
					//tomcat naming 
					Map<String, Map<String, String>> injectionMap = buildInjectionMap(
							getIgnoreAnnotations() ? new NamingResourcesImpl(): getNamingResources());
					this.setInstanceManager(
							new DefaultSipInstanceManager(
									getNamingContextListener().getEnvContext(),
									injectionMap, this, this.getLoader().getClassLoader()));
				} 
			}		
			getServletContext().setAttribute(InstanceManager.class.getName(), getInstanceManager());
			//set the session manager on the specific sipstandardmanager to handle converged http sessions
			if(getManager() instanceof SipManager) {
				((SipManager)getManager()).setMobicentsSipFactory(
						sipApplicationDispatcher.getSipFactory());
				((CatalinaSipManager)manager).setContext(this);
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
				hasDistributableManager = true;
				if(logger.isInfoEnabled()) {
					logger.info("this context contains a manager that allows applications to work in a distributed environment");
				}
			}
			if(logger.isInfoEnabled()) {
				logger.info("sip application session timeout for this context is " + sipApplicationSessionTimeout + " minutes");
			}
			if(logger.isInfoEnabled()) {
				logger.info("sip context started " + getName());
			}			
		} else {
			if(logger.isInfoEnabled()) {
				logger.info("sip context didn't started due to errors " + getName());
			}
		}							
	}
	
	private Map<String, Map<String, String>> buildInjectionMap(NamingResourcesImpl namingResources) {
        Map<String, Map<String, String>> injectionMap = new HashMap<>();
        for (Injectable resource: namingResources.findLocalEjbs()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource: namingResources.findEjbs()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource: namingResources.findEnvironments()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource: namingResources.findMessageDestinationRefs()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource: namingResources.findResourceEnvRefs()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource: namingResources.findResources()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource: namingResources.findServices()) {
            addInjectionTarget(resource, injectionMap);
        }
        return injectionMap;
    }
	
    /**
     * Set the appropriate context attribute for our work directory.
     */
    private void postWorkDirectory() {

        // Acquire (or calculate) the work directory path
        String workDir = getWorkDir();
        if (workDir == null || workDir.length() == 0) {

            // Retrieve our parent (normally a host) name
            String hostName = null;
            String engineName = null;
            String hostWorkDir = null;
            Container parentHost = getParent();
            if (parentHost != null) {
                hostName = parentHost.getName();
                if (parentHost instanceof StandardHost) {
                    hostWorkDir = ((StandardHost)parentHost).getWorkDir();
                }
                Container parentEngine = parentHost.getParent();
                if (parentEngine != null) {
                   engineName = parentEngine.getName();
                }
            }
            if ((hostName == null) || (hostName.length() < 1))
                hostName = "_";
            if ((engineName == null) || (engineName.length() < 1))
                engineName = "_";

            String temp = getBaseName();
            if (temp.startsWith("/"))
                temp = temp.substring(1);
            temp = temp.replace('/', '_');
            temp = temp.replace('\\', '_');
            if (temp.length() < 1)
                temp = ContextName.ROOT_NAME;
            if (hostWorkDir != null ) {
                workDir = hostWorkDir + File.separator + temp;
            } else {
                workDir = "work" + File.separator + engineName +
                    File.separator + hostName + File.separator + temp;
            }
            setWorkDir(workDir);
        }

        // Create this directory if necessary
        File dir = new File(workDir);
        if (!dir.isAbsolute()) {
            String catalinaHomePath = null;
            try {
                catalinaHomePath = getCatalinaBase().getCanonicalPath();
                dir = new File(catalinaHomePath, workDir);
            } catch (IOException e) {
                logger.warn(sm.getString("standardContext.workCreateException",
                        workDir, catalinaHomePath, getName()), e);
            }
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            logger.warn(sm.getString("standardContext.workCreateFail", dir,
                    getName()));
        }

        // Set the appropriate servlet context attribute
        if (context == null) {
            getServletContext();
        }
        context.setAttribute(ServletContext.TEMPDIR, dir);
//        context.setAttributeReadOnly(ServletContext.TEMPDIR);
    }

	@Override
	public ServletContext getServletContext() {	
		if (context == null) {
			context = new ConvergedApplicationContext(this);
			if (getAltDDName() != null)
				context.setAttribute(Globals.ALT_DD_ATTR,getAltDDName());
		}

		return ((ConvergedApplicationContext)context).getFacade();

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

		if(!sipJNDIContextLoaded) {
			loadSipJNDIContext();
		}

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
					getLogger().error
					(sm.getString("standardContext.listenerStart",
							servletContextListener.getClass().getName()), t);
					ok = false;
				}

				// TODO Annotation processing                 
			}
		}
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
					getLogger().error
					(sm.getString("standardContext.listenerStop",
							servletContextListener.getClass().getName()), t);
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
//	public String getBasePath() {
//		String docBase = null;
//		Container container = this;
//		while (container != null) {
//			if (container instanceof Host)
//				break;
//			container = container.getParent();
//		}
//		File file = new File(getDocBase());
//		if (!file.isAbsolute()) {
//			if (container == null) {
//				docBase = (new File(engineBase(), getDocBase())).getPath();
//			} else {
//				// Use the "appBase" property of this container
//				String appBase = ((Host) container).getAppBase();
//				file = new File(appBase);
//				if (!file.isAbsolute())
//					file = new File(engineBase(), appBase);
//				docBase = (new File(file, getDocBase())).getPath();
//			}
//		} else {
//			docBase = file.getPath();
//		}
//		return docBase;
//	}

	@Override
	public synchronized void stopInternal() throws LifecycleException {
		if(logger.isInfoEnabled()) {
			logger.info("Stopping the sip context" + name);
		}
		if(manager instanceof SipManager) {
			((SipManager)manager).dumpSipSessions();
			((SipManager)manager).dumpSipApplicationSessions();
			logger.info("number of active sip sessions : " + ((SipManager)manager).getActiveSipSessions()); 
			logger.info("number of active sip application sessions : " + ((SipManager)manager).getActiveSipApplicationSessions());
		}		
		super.stopInternal();		
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
		sipJNDIContextLoaded = false;
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
		// Issue 48 (https://bitbucket.org/telestax/telscale-sip-servlets/issue/48/sipstandardservice-stopgracefuly-for)
		if(gracefulStopFuture != null) {
			gracefulStopFuture.cancel(false);
			gracefulStopFuture = null;
			if(logger.isDebugEnabled()) {
				logger.debug("context graceful task cancelled " + getName());
			}
		}
		// Issue 1478 : nullify the ref to avoid reusing it
		timerService = null;
		getServletContext().setAttribute(javax.servlet.sip.SipServlet.TIMER_SERVICE, null);
		setLoader(null);
		// not needed since the JNDI will be destroyed automatically
		//		if(isUseNaming()) {
		//			fireContainerEvent(SipNamingContextListener.NAMING_CONTEXT_SIP_FACTORY_REMOVED_EVENT, sipFactoryFacade);
		//			fireContainerEvent(SipNamingContextListener.NAMING_CONTEXT_SIP_SESSIONS_UTIL_REMOVED_EVENT, sipSessionsUtil);
		//			fireContainerEvent(SipNamingContextListener.NAMING_CONTEXT_TIMER_SERVICE_REMOVED_EVENT, TimerServiceImpl.getInstance());
		//			fireContainerEvent(SipNamingContextListener.NAMING_CONTEXT_SIP_SUBCONTEXT_REMOVED_EVENT, null);
		//		}  else {
		//        	try {
		//				InitialContext iniCtx = new InitialContext();
		//				Context envCtx = (Context) iniCtx.lookup("java:comp/env");
		//				// jboss or other kind of naming
		//				SipNamingContextListener.removeSipFactory(envCtx, sipFactoryFacade);
		//				SipNamingContextListener.removeSipSessionsUtil(envCtx, sipSessionsUtil);
		//				SipNamingContextListener.removeTimerService(envCtx, TimerServiceImpl.getInstance());
		//				SipNamingContextListener.removeSipSubcontext(envCtx);
		//			} catch (NamingException e) {
		//				//It is possible that the context has already been removed so no problem,
		//				//we are stopping anyway
		////				logger.error("Impossible to get the naming context ", e);				
		//			}	        	
		//        }		
		if(logger.isInfoEnabled()) {
			logger.info("sip context stopped " + name);
		}
	}

	@Override
	public boolean loadOnStartup(Container[] containers) {
		if(!sipJNDIContextLoaded) {
			loadSipJNDIContext();
		}
		return super.loadOnStartup(containers);	
	}

	protected void loadSipJNDIContext() {
		if(getInstanceManager() instanceof SipInstanceManager) {
			if(getNamingContextListener() != null) {
				getSipInstanceManager().setContext(getNamingContextListener().getEnvContext());
			} else {
				try {
					InitialContext iniCtx = new InitialContext();
					Context envCtx = (Context) iniCtx.lookup("java:comp/env");
					getSipInstanceManager().setContext(envCtx);
				} catch (NamingException e) {
					logger.error("Impossible to get the naming context ", e);
					throw new IllegalStateException(e);
				}	  			
			}
		}
		if(isUseNaming()) {
			fireContainerEvent(SipNamingContextListener.NAMING_CONTEXT_SIP_SUBCONTEXT_ADDED_EVENT, null);
			fireContainerEvent(SipNamingContextListener.NAMING_CONTEXT_APPNAME_SUBCONTEXT_ADDED_EVENT, null);
			fireContainerEvent(SipNamingContextListener.NAMING_CONTEXT_SIP_FACTORY_ADDED_EVENT, sipFactoryFacade);
			fireContainerEvent(SipNamingContextListener.NAMING_CONTEXT_SIP_SESSIONS_UTIL_ADDED_EVENT, sipSessionsUtil);
			fireContainerEvent(SipNamingContextListener.NAMING_CONTEXT_TIMER_SERVICE_ADDED_EVENT, timerService);			
		} else {
			try {
				InitialContext iniCtx = new InitialContext();
				Context envCtx = (Context) iniCtx.lookup("java:comp/env");
				// jboss or other kind of naming
				SipNamingContextListener.addSipSubcontext(envCtx);
				SipNamingContextListener.addAppNameSubContext(envCtx, applicationName);
				SipNamingContextListener.addSipFactory(envCtx, applicationName, sipFactoryFacade);
				SipNamingContextListener.addSipSessionsUtil(envCtx, applicationName, sipSessionsUtil);
				SipNamingContextListener.addTimerService(envCtx, applicationName, timerService);
			} catch (NamingException e) {
				logger.error("Impossible to get the naming context ", e);
				throw new IllegalStateException(e);
			}	        			
		}
		sipJNDIContextLoaded  = true;
	}

	@Override
	public Wrapper createWrapper() {		
		return super.createWrapper();
	}		

	@Override
	public void addChild(Container container) {
		if(children.get(container.getName()) == null) {
			if(container instanceof SipServletImpl) {
				this.addChild((SipServletImpl)container);
			} else {
				super.addChild(container);
			}
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
		this.isMainServlet = true;
		servletHandler = mainServlet;
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
		if(manager instanceof SipManager && sipApplicationDispatcher != null) {
			((SipManager)manager).setMobicentsSipFactory(
					sipApplicationDispatcher.getSipFactory()); 
			((CatalinaSipManager)manager).setContainer(this);
		}
		if(manager instanceof DistributableSipManager) {
			hasDistributableManager = true;
			if(logger.isInfoEnabled()) {
				logger.info("this context contains a manager that allows applications to work in a distributed environment");
			}
		}
		super.setManager(manager);
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

//	@Override
//	public String getInfo() {
//		return info;
//	}

	/**
	 * Notifies the sip servlet listeners that the servlet has been initialized
	 * and that it is ready for service
	 * @param sipContext the sip context of the application where the listeners reside.
	 * @return true if all listeners have been notified correctly
	 */
	public boolean notifySipContextListeners(SipContextEvent event) {
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
				timerService.start();
			}
			if(!proxyTimerService.isStarted()) {
				proxyTimerService.start();
			}
			if(!sasTimerService.isStarted()) {
				sasTimerService.start();
			}
		}
		enterSipApp(null, null, false, true);
		boolean batchStarted = enterSipAppHa(true);
		// https://github.com/Mobicents/sip-servlets/issues/52
		List<MobicentsSipServlet> sipServlets = new ArrayList<MobicentsSipServlet>(childrenMap.values());
		Collections.sort(sipServlets, new SipServletLoadOnStartupComparator());
		
		try {
			for (MobicentsSipServlet container : sipServlets) {
				if(logger.isDebugEnabled()) {
					logger.debug("container " + container.getName() + ", class : " + container.getClass().getName());
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
		return ok;
	}

	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.core.SipContext#enterSipApp(org.mobicents.servlet.sip.core.session.MobicentsSipApplicationSession, org.mobicents.servlet.sip.core.session.MobicentsSipSession, boolean, boolean)
	 */
	public void enterSipApp(MobicentsSipApplicationSession sipApplicationSession, MobicentsSipSession sipSession, boolean checkIsManagedThread, boolean isContainerManaged) {		
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

	public ConcurrencyControlMode getConcurrencyControlMode() {		
		return concurrencyControlMode;
	}

	public void setConcurrencyControlMode(ConcurrencyControlMode mode) {
		this.concurrencyControlMode = mode;
		if(concurrencyControlMode != null && logger.isInfoEnabled()) {
			logger.info("Concurrency Control set to " + concurrencyControlMode.toString() + " for application " + applicationName);
		}
	}

	public SipRubyController getSipRubyController() {
		return null;
	}

	public void setSipRubyController(SipRubyController rubyController) {
		throw new UnsupportedOperationException("ruby applications are not supported on Tomcat or JBoss 4.X versions");
	}

	public SipApplicationSessionTimerService getSipApplicationSessionTimerService() {
		return sasTimerService;
	}

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

	@Override
	public String getEngineName() {
		// TODO Auto-generated method stub
		return null;
	}

	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.core.SipContext#enterSipAppHa(boolean)
	 */
	@Override
	public boolean enterSipAppHa(boolean startCacheActivity) {
		// we don't support ha features, so nothing to do here
		return false;
	}

	@Override
	public void exitSipAppHa(MobicentsSipServletRequest request, MobicentsSipServletResponse response, boolean batchStarted) {
		// we don't support ha features, so nothing to do here
	}

	/**
	 * Copied from Tomcat 7 StandardContext
	 * 
	 * @param resource
	 * @param injectionMap
	 */
	private void addInjectionTarget(Injectable resource, Map<String, Map<String, String>> injectionMap) {
		List<InjectionTarget> injectionTargets = resource.getInjectionTargets();
		if (injectionTargets != null && injectionTargets.size() > 0) {
			String jndiName = resource.getName();
			for (InjectionTarget injectionTarget: injectionTargets) {
				String clazz = injectionTarget.getTargetClass();
				Map<String, String> injections = injectionMap.get(clazz);
				if (injections == null) {
					injections = new HashMap<String, String>();
					injectionMap.put(clazz, injections);
				}
				injections.put(injectionTarget.getTargetName(), jndiName);
			}
		}
	}

	@Override
	public SipInstanceManager getSipInstanceManager() {
		return (SipInstanceManager) super.getInstanceManager();
	}

	@Override
	public void setInstanceManager(InstanceManager instanceManager) {
		super.setInstanceManager(instanceManager);
	}

	public ClassLoader getSipContextClassLoader() {
		return getLoader().getClassLoader();
	}

	public boolean isPackageProtectionEnabled() {		
		return SecurityUtil.isPackageProtectionEnabled();
	}

	public boolean authorize(MobicentsSipServletRequest request) {
		return sipSecurityUtils.authorize(request);
	}

	public SipDigestAuthenticator getDigestAuthenticator() {
		return sipDigestAuthenticator;
	}

	@Override
	public void enterSipContext() {
		final ClassLoader cl = getSipContextClassLoader();
		Thread.currentThread().setContextClassLoader(cl);
	}

	@Override
	public void exitSipContext(ClassLoader oldClassLoader) {
		Thread.currentThread().setContextClassLoader(oldClassLoader);
	}

	@Override
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.core.SipContext#stopGracefully(long)
	 */
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
				// if the time to Wait is < to the gracefulStopTaskInterval then we schedule the task directly once to the time to wait
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
