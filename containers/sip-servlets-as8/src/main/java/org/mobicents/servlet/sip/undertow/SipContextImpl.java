/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2015, Telestax Inc and individual contributors
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
package org.mobicents.servlet.sip.undertow;

import io.undertow.server.session.SessionManager;
import io.undertow.servlet.api.Deployment;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.api.ThreadSetupAction.Handle;
import io.undertow.servlet.core.Lifecycle;
import io.undertow.servlet.core.ManagedFilter;
import io.undertow.servlet.core.ManagedServlet;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.servlet.Servlet;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.TimerService;

import org.apache.http.MethodNotSupportedException;
import org.apache.log4j.Logger;
import org.mobicents.io.undertow.servlet.api.DeploymentInfoFacade;
import org.mobicents.servlet.sip.SipConnector;
import org.mobicents.servlet.sip.annotation.ConcurrencyControlMode;
import org.mobicents.servlet.sip.core.MobicentsSipServlet;
import org.mobicents.servlet.sip.core.SipApplicationDispatcher;
import org.mobicents.servlet.sip.core.SipContext;
import org.mobicents.servlet.sip.core.SipContextEvent;
import org.mobicents.servlet.sip.core.SipContextEventType;
import org.mobicents.servlet.sip.core.SipListeners;
import org.mobicents.servlet.sip.core.SipManager;
import org.mobicents.servlet.sip.core.descriptor.MobicentsSipServletMapping;
import org.mobicents.servlet.sip.core.message.MobicentsSipServletRequest;
import org.mobicents.servlet.sip.core.message.MobicentsSipServletResponse;
import org.mobicents.servlet.sip.core.security.MobicentsSipLoginConfig;
import org.mobicents.servlet.sip.core.security.SipDigestAuthenticator;
import org.mobicents.servlet.sip.core.session.MobicentsSipApplicationSession;
import org.mobicents.servlet.sip.core.session.MobicentsSipSession;
import org.mobicents.servlet.sip.core.session.MobicentsSipSessionsUtil;
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
import org.mobicents.servlet.sip.dns.MobicentsDNSResolver;
import org.mobicents.servlet.sip.listener.SipConnectorListener;
import org.mobicents.servlet.sip.message.SipFactoryFacade;
import org.mobicents.servlet.sip.message.SipFactoryImpl;
import org.mobicents.servlet.sip.ruby.SipRubyController;
import org.mobicents.servlet.sip.startup.ConvergedServletContextImpl;
import org.mobicents.servlet.sip.undertow.security.SipSecurityUtils;
import org.mobicents.servlet.sip.undertow.security.authentication.SipDigestAuthenticationMechanism;
import org.wildfly.extension.undertow.Host;
import org.wildfly.extension.undertow.ListenerService;

/**
 *
 * This class is based on org.mobicents.servlet.sip.startup.SipStandardContext from sip-servlet-as7 project, re-implemented for
 * jboss as8 (wildfly) by:
 *
 * @author kakonyi.istvan@alerant.hu
 */
public class SipContextImpl implements SipContext {

    private static final Logger logger = Logger.getLogger(SipContextImpl.class);

    // as mentionned per JSR 289 Section 6.1.2.1 default lifetime for an
    // application session is 3 minutes
    private static int DEFAULT_LIFETIME = 3;

    private Deployment deployment;

    protected DeploymentInfoFacade deploymentInfoFacade;
    protected transient SipApplicationDispatcher sipApplicationDispatcher = null;

    protected boolean hasDistributableManager;
    // timer service used to schedule sip application session expiration timer
    protected transient SipApplicationSessionTimerService sasTimerService = null;
    protected TimerServiceType sasTimerServiceType = null;
    // timer service used to schedule sip servlet originated timer tasks
    protected transient SipServletTimerService timerService = null;
    // timer service used to schedule proxy timer tasks
    protected transient ProxyTimerService proxyTimerService = null;
    protected TimerServiceType proxyTimerServiceType = null;
    protected transient SipListeners sipListeners;
    protected transient SipFactoryFacade sipFactoryFacade;
    protected transient SipSessionsUtilImpl sipSessionsUtil;
    protected transient SipSecurityUtils sipSecurityUtils;
    protected transient SipDigestAuthenticator sipDigestAuthenticator;
    protected transient String securityDomain;

    protected String displayName;

    private transient ThreadLocal<SipApplicationSessionCreationThreadLocal> sipApplicationSessionsAccessedThreadLocal = new ThreadLocal<SipApplicationSessionCreationThreadLocal>();
    // http://code.google.com/p/mobicents/issues/detail?id=2534 &&
    // http://code.google.com/p/mobicents/issues/detail?id=2526
    private transient ThreadLocal<Boolean> isManagedThread = new ThreadLocal<Boolean>();

    ConvergedServletContextImpl context;

    private ClassLoader sipContextClassLoader;
    private Host hostOfDeployment;
    List<ListenerService<?>> webServerListeners = new LinkedList<>();

    // http://code.google.com/p/sipservlets/issues/detail?id=195
    private ScheduledFuture<?> gracefulStopFuture;
    
    // default constructor:
    public SipContextImpl() {
    }

    public void init(Deployment deployment, ClassLoader sipContextClassLoader) throws ServletException {
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing the sip context");
        }

        this.setSipApplicationSessionTimeout(DEFAULT_LIFETIME);
        // pipeline.setBasic(new SipStandardContextValve());
        this.sipListeners = new UndertowSipListenersHolder(this);
        this.deploymentInfoFacade.setChildrenMap(new HashMap<String, MobicentsSipServlet>());
        this.deploymentInfoFacade.setChildrenMapByClassName(new HashMap<String, MobicentsSipServlet>());
        int idleTime = this.getSipApplicationSessionTimeout();
        if (idleTime <= 0) {
            idleTime = 1;
        }
        this.deploymentInfoFacade.setMainServlet(false);

        if (this.sipContextClassLoader == null) {
            this.sipContextClassLoader = sipContextClassLoader;
        }

        addDeploymentToContext(deployment);
        // if (this.getParent() != null) {
        // // Add the main configuration listener for sip applications
        // LifecycleListener sipConfigurationListener = new ();
        // this.addLifecycleListener(sipConfigurationListener);
        // setDelegate(true);
        // }
        // call the super method to correctly initialize the context and fire
        // up the
        // init event on the new registered SipContextConfig, so that the
        // standardcontextconfig
        // is correctly initialized too

        prepareServletContext();

        if (logger.isDebugEnabled()) {
            logger.debug("sip context Initialized");
        }
    }

    private void addDeploymentToContext(Deployment deployment) throws ServletException {
        this.deployment = deployment;
    }

    public synchronized void start() throws ServletException {
        if (logger.isDebugEnabled()) {
            logger.debug("Starting the sip context " + this.getApplicationName());
        }

        prepareServletContext();

        // FIXME: kakonyii: DistributableSipManager handling implementation
        hasDistributableManager = false;
        ((UndertowSipManager) this.getSessionManager()).setMobicentsSipFactory(sipApplicationDispatcher.getSipFactory());
        ((UndertowSipManager) this.getSessionManager()).setContainer(this);

        // JSR 289 16.2 Servlet Selection
        // When using this mechanism (the main-servlet) for servlet selection,
        // if there is only one servlet in the application then this
        // declaration is optional and the lone servlet becomes the main servlet
        String mainServlet = this.getMainServlet();
        Map<String, MobicentsSipServlet> childrenMap = this.getChildrenMap();
        if ((mainServlet == null || mainServlet.length() < 1) && childrenMap.size() == 1) {
            setMainServlet(childrenMap.keySet().iterator().next());
        }

        sipSecurityUtils = new SipSecurityUtils(this);

        String realmName = null;
        if (((SipLoginConfig) this.getSipLoginConfig()) != null) {
            realmName = ((SipLoginConfig) this.getSipLoginConfig()).getRealmName();
        }
        sipDigestAuthenticator = new SipDigestAuthenticationMechanism(realmName, sipApplicationDispatcher.getSipFactory()
                .getHeaderFactory());
        // JSR 289 Section 2.1.1 Step 3.Invoke SipApplicationRouter.applicationDeployed() for this application.
        // called implicitly within sipApplicationDispatcher.addSipApplication
        sipApplicationDispatcher.addSipApplication(this.getApplicationName(), this);

        // lests starts sipServlets too!!
        ArrayList<Lifecycle> lifecycles = new ArrayList<>();
        for (MobicentsSipServlet sipServlet : this.getChildrenMap().values()) {
            lifecycles.add((SipServletImpl) sipServlet);
        }
        for (Lifecycle object : lifecycles) {
            object.start();
        }
        final TreeMap<Integer, List<ManagedServlet>> loadOnStartup = new TreeMap<>();
        for (MobicentsSipServlet sipServlet : this.getChildrenMap().values()) {
            SipServletImpl servlet = (SipServletImpl) sipServlet;

            Integer loadOnStartupNumber = servlet.getServletInfo().getLoadOnStartup();
            if (loadOnStartupNumber != null) {
                if (loadOnStartupNumber < 0) {
                    continue;
                }
                List<ManagedServlet> list = loadOnStartup.get(loadOnStartupNumber);
                if (list == null) {
                    loadOnStartup.put(loadOnStartupNumber, list = new ArrayList<>());
                }
                list.add(servlet);
            }
        }
        for (Map.Entry<Integer, List<ManagedServlet>> load : loadOnStartup.entrySet()) {
            for (ManagedServlet servlet : load.getValue()) {
                servlet.createServlet();
            }
        }
        if (deployment.getDeploymentInfo().isEagerFilterInit()) {
            for (ManagedFilter filter : deployment.getFilters().getFilters().values()) {
                filter.createFilter();
            }
        }

        if (logger.isDebugEnabled()) {
            logger.debug("sip application session timeout for this context is " + this.getSipApplicationSessionTimeout()
                    + " minutes");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("http session timeout for this context is "
                    + this.deploymentInfoFacade.getDeploymentInfo().getDefaultSessionTimeout() + " minutes");
        }
        if (logger.isDebugEnabled()) {
            logger.debug("sip context started " + this.getApplicationName());
        }
    }

    public synchronized void stop() throws ServletException {
        String name = this.getApplicationName();
        if (logger.isDebugEnabled()) {
            logger.debug("Stopping the sip context " + name);
        }

        ((UndertowSipManager) this.getSessionManager()).dumpSipSessions();
        ((UndertowSipManager) this.getSessionManager()).dumpSipApplicationSessions();
        logger.warn("number of active sip sessions : " + ((UndertowSipManager) this.getSessionManager()).getActiveSipSessions());
        logger.warn("number of active sip application sessions : "
                + ((UndertowSipManager) this.getSessionManager()).getActiveSipApplicationSessions());

        // lests stop sipServlets lifecycle objects:
        ArrayList<Lifecycle> lifecycles = new ArrayList<>();
        for (MobicentsSipServlet sipServlet : this.getChildrenMap().values()) {
            lifecycles.add((SipServletImpl) sipServlet);
        }
        for (Lifecycle object : lifecycles) {
            object.stop();
        }

        // this should happen after so that applications can still do some processing
        // in destroy methods to notify that context is getting destroyed and app removed
        sipListeners.deallocateServletsActingAsListeners();
        this.deploymentInfoFacade.getSipApplicationListeners().clear();
        this.deploymentInfoFacade.getSipServletMappings().clear();

        this.getChildrenMap().clear();
        this.deploymentInfoFacade.getChildrenMapByClassName().clear();
        if (sipApplicationDispatcher != null) {
            if (this.getApplicationName() != null) {
                sipApplicationDispatcher.removeSipApplication(this.getApplicationName());
            } else {
                logger.error("the application name is null for the following context : " + name);
            }
        }
        if (sasTimerService != null && sasTimerService.isStarted()) {
            sasTimerService.stop();
        }
        // Issue 1478 : nullify the ref to avoid reusing it
        sasTimerService = null;
        // Issue 1791 : don't check is the service is started it makes the stop
        // of tomcat hang
        if (timerService != null) {
            timerService.stop();
        }
        if (proxyTimerService != null) {
            proxyTimerService.stop();
        }
        // Issue 1478 : nullify the ref to avoid reusing it
        timerService = null;
        getServletContext().setAttribute(javax.servlet.sip.SipServlet.TIMER_SERVICE, null);
        if (logger.isDebugEnabled()) {
            logger.debug("sip context stopped " + name);
        }
    }

    public boolean listenerStop() {
        boolean ok = true;
        if (logger.isDebugEnabled())
            logger.debug("Sending application stop events");

        List<ServletContextListener> servletContextListeners = sipListeners.getServletContextListeners();
        if (servletContextListeners != null) {
            ServletContextEvent event = new ServletContextEvent(getServletContext());
            for (ServletContextListener servletContextListener : servletContextListeners) {
                if (servletContextListener == null)
                    continue;

                try {
                    // FIXME: fireContainerEvent("beforeContextDestroyed", servletContextListener);
                    servletContextListener.contextDestroyed(event);
                    // FIXME: fireContainerEvent("afterContextDestroyed", servletContextListener);
                } catch (Throwable t) {
                    // FIXME: fireContainerEvent("afterContextDestroyed", servletContextListener);
                    // getLogger().error
                    // (sm.getString("standardContext.listenerStop",
                    // servletContextListener.getClass().getName()), t);
                    // getLogger().error
                    // (MESSAGES.errorSendingContextDestroyedEvent(servletContextListener.getClass().getName()), t);

                    logger.error("Failed to destroy servletContext.", t);
                    t.printStackTrace();
                    ok = false;
                }
            }
        }

        sipListeners.clean();

        return ok;
    }

    public void prepareServletContextServices() {
        if (sipFactoryFacade == null) {
            sipFactoryFacade = new SipFactoryFacade((SipFactoryImpl) sipApplicationDispatcher.getSipFactory(), this);
        }
        if (sipSessionsUtil == null) {
            sipSessionsUtil = new SipSessionsUtilImpl(this);
        }
        if (timerService == null) {
            // FIXME: distributable not supported
            // if(getDistributable() && hasDistributableManager) {
            // if(logger.isInfoEnabled()) {
            // logger.info("Using the Fault Tolerant Timer Service to schedule fault tolerant timers in a distributed environment");
            // }
            // timerService = new FaultTolerantTimerServiceImpl((DistributableSipManager)getSipManager());
            // } else {
            // timerService = new TimerServiceImpl();
            // }
            timerService = new TimerServiceImpl(sipApplicationDispatcher.getSipService(), getApplicationName());
        }
        if (proxyTimerService == null) {
            if(proxyTimerServiceType != null && proxyTimerServiceType == TimerServiceType.STANDARD) {
                proxyTimerService = new ProxyTimerServiceImpl(getApplicationName());
            } else if(proxyTimerServiceType != null && proxyTimerServiceType == TimerServiceType.DEFAULT) {
                proxyTimerService = new DefaultProxyTimerService(getApplicationName());
            } else {
                proxyTimerService = new ProxyTimerServiceImpl(getApplicationName());
            }
        }
        if (sasTimerService == null /*kakonyii: prevent creating sasTimerService's threads multiple times by commenting this out: || !sasTimerService.isStarted()*/) {
            // FIXME: distributable not supported
            // distributable if(getDistributable() && hasDistributableManager) {
            // sasTimerService = new FaultTolerantSasTimerService((DistributableSipManager)getSipManager(), 4);
            // } else {
            // sasTimerService = new StandardSipApplicationSessionTimerService();
            // }
            if(sasTimerServiceType != null && sasTimerServiceType == TimerServiceType.STANDARD){
                sasTimerService = new StandardSipApplicationSessionTimerService(getApplicationName());
            }else if (sasTimerServiceType != null && sasTimerServiceType == TimerServiceType.DEFAULT){
                sasTimerService = new DefaultSipApplicationSessionTimerService(getApplicationName());
            }else{
                sasTimerService = new StandardSipApplicationSessionTimerService(getApplicationName());
            }
        }
    }

    protected void prepareServletContext() throws ServletException {
        this.prepareServletContextServices();

        // needed when restarting applications through the tomcat manager
        this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.SIP_FACTORY, sipFactoryFacade);
        this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.TIMER_SERVICE, timerService);
        this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.SUPPORTED,
                Arrays.asList(sipApplicationDispatcher.getExtensionsSupported()));
        this.getServletContext().setAttribute("javax.servlet.sip.100rel", Boolean.TRUE);
        this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.SUPPORTED_RFCs,
                Arrays.asList(sipApplicationDispatcher.getRfcSupported()));
        this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.SIP_SESSIONS_UTIL, sipSessionsUtil);
        this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.OUTBOUND_INTERFACES,
                sipApplicationDispatcher.getOutboundInterfaces());
        this.getServletContext().setAttribute("org.mobicents.servlet.sip.SIP_CONNECTORS",
                sipApplicationDispatcher.getSipService().findSipConnectors());
        this.getServletContext().setAttribute("org.mobicents.servlet.sip.DNS_RESOLVER",
                new MobicentsDNSResolver(sipApplicationDispatcher.getDNSServerLocator()));
    }

    public void addChild(SipServletImpl sipServletImpl) {
        Map<String, MobicentsSipServlet> childrenMap = this.deploymentInfoFacade.getChildrenMap();
        Map<String, MobicentsSipServlet> childrenMapByClassName = this.deploymentInfoFacade.getChildrenMapByClassName();
        SipServletImpl existingServlet = (SipServletImpl) childrenMap.get(sipServletImpl.getName());
        if (existingServlet != null) {
            logger.warn(sipServletImpl.getName() + " servlet already present, removing the previous one. "
                    + "This might be due to the fact that the definition of the servlet "
                    + "is present both in annotations and in sip.xml");
            // we remove the previous one (annoations) because it may not have init parameters that has been defined in
            // sip.xml
            // See TCK Test ContextTest.testContext1
            childrenMap.remove(sipServletImpl.getName());
            childrenMapByClassName.remove(sipServletImpl.getServletInfo().getName());
            // super.removeChild(existingServlet);
        }
        childrenMap.put(sipServletImpl.getName(), sipServletImpl);
        childrenMapByClassName.put(sipServletImpl.getServletInfo().getName(), sipServletImpl);
        // super.addChild(sipServletImpl);
    }

    public void removeChild(SipServletImpl sipServletImpl) {
        // super.removeChild(sipServletImpl);
        Map<String, MobicentsSipServlet> childrenMap = this.deploymentInfoFacade.getChildrenMap();
        Map<String, MobicentsSipServlet> childrenMapByClassName = this.deploymentInfoFacade.getChildrenMapByClassName();

        childrenMap.remove(sipServletImpl.getName());
        childrenMapByClassName.remove(sipServletImpl.getServletInfo().getName());
    }

    public void setApplicationDispatcher(SipApplicationDispatcher dispatcher) throws ServletException {
        sipApplicationDispatcher = dispatcher;
        if (sipApplicationDispatcher == null) {
            throw new ServletException("cannot find any application dispatcher for this context " + this.getApplicationName());
        }
    }

    public boolean contextListenerStart() throws ServletException{
        if (logger.isDebugEnabled())
            logger.debug("Configuring sip listeners");

        // Instantiate the required listeners
        ClassLoader loader = this.getSipContextClassLoader();
        boolean ok = sipListeners.loadListeners(findSipApplicationListeners(), loader);
        if (!ok) {
            return ok;
        }

        List<ServletContextListener> servletContextListeners = sipListeners.getServletContextListeners();
        if (servletContextListeners != null) {
            ServletContextEvent event = new ServletContextEvent(getServletContext());
            for (ServletContextListener servletContextListener : servletContextListeners) {
                if (servletContextListener == null)
                    continue;

                try {
                    // FIXME: fireContainerEvent("beforeContextInitialized", servletContextListener);
                    servletContextListener.contextInitialized(event);
                    // FIXME: fireContainerEvent("afterContextInitialized", servletContextListener);
                } catch (Throwable t) {
                    // FIXME: fireContainerEvent("afterContextInitialized", servletContextListener);
                    // getLogger().error
                    // (sm.getString("standardContext.listenerStart",
                    // servletContextListener.getClass().getName()), t);
                    // getLogger().error
                    // (MESSAGES.errorSendingContextInitializedEvent(servletContextListener.getClass().getName()), t);
                    ok = false;
                    logger.error("Failed to initialize context!", t);
                }
            }
        }

        return (ok);
    }

    public SessionManager getSessionManager() {
        return this.deployment.getSessionManager();
    }

    public ConvergedServletContextImpl getServletContext() {
        if (context == null) {
            context = new ConvergedServletContextImpl(deployment.getServletContext());
            context.addSipContext(this);
        }
        return context;
    }

    @Override
    public String getApplicationName() {
        return this.deploymentInfoFacade.getApplicationName();
    }

    @Override
    public String getApplicationNameHashed() {
        return sipApplicationDispatcher.getHashFromApplicationName(getApplicationName());
    }

    @Override
    public boolean hasDistributableManager() {
        return this.hasDistributableManager;
    }

    @Override
    public void setApplicationName(String applicationName) {
        deploymentInfoFacade.setApplicationName(applicationName);
    }

    @Override
    public String getDescription() {
        return this.deploymentInfoFacade.getDescription();
    }

    @Override
    public void setDescription(String description) {
        deploymentInfoFacade.setDescription(description);

    }

    @Override
    public String getLargeIcon() {
        return this.deploymentInfoFacade.getLargeIcon();
    }

    @Override
    public void setLargeIcon(String largeIcon) {
        this.deploymentInfoFacade.setLargeIcon(largeIcon);
    }

    @Override
    public SipListeners getListeners() {
        return this.sipListeners;
    }

    @Override
    public void setListeners(SipListeners listeners) {
        this.sipListeners = (UndertowSipListenersHolder) listeners;

    }

    @Override
    public boolean isMainServlet() {
        return deploymentInfoFacade.isMainServlet();
    }

    @Override
    public String getMainServlet() {
        return deploymentInfoFacade.getMainServlet();
    }

    @Override
    public void setMainServlet(String mainServlet) {
        this.deploymentInfoFacade.setMainServlet(mainServlet);
        this.deploymentInfoFacade.setServletHandler(mainServlet);
        this.deploymentInfoFacade.setMainServlet(true);
    }

    @Override
    public void setServletHandler(String servletHandler) {
        this.deploymentInfoFacade.setServletHandler(servletHandler);

    }

    @Override
    public String getServletHandler() {
        return this.deploymentInfoFacade.getServletHandler();
    }

    @Override
    public int getProxyTimeout() {
        return this.deploymentInfoFacade.getProxyTimeout();
    }

    @Override
    public void setProxyTimeout(int proxyTimeout) {
        this.deploymentInfoFacade.setProxyTimeout(proxyTimeout);

    }

    @Override
    public int getSipApplicationSessionTimeout() {
        return this.deploymentInfoFacade.getSipApplicationSessionTimeout();
    }

    @Override
    public void setSipApplicationSessionTimeout(int proxyTimeout) {
        this.deploymentInfoFacade.setSipApplicationSessionTimeout(proxyTimeout);

    }

    @Override
    public String getSmallIcon() {
        return this.deploymentInfoFacade.getSmallIcon();
    }

    @Override
    public void setSmallIcon(String smallIcon) {
        this.deploymentInfoFacade.setSmallIcon(smallIcon);

    }

    @Override
    public void addSipApplicationListener(String listener) {
        this.deploymentInfoFacade.addSipApplicationListener(listener);
        // FIXME: fireContainerEvent("addSipApplicationListener", listener);

    }

    @Override
    public void removeSipApplicationListener(String listener) {
        this.deploymentInfoFacade.removeSipApplicationListener(listener);
        // FIXME: fireContainerEvent("removeSipApplicationListener", listener);
    }

    @Override
    public String[] findSipApplicationListeners() {
        return this.deploymentInfoFacade.findSipApplicationListeners();
    }

    @Override
    public Method getSipApplicationKeyMethod() {
        return this.deploymentInfoFacade.getSipApplicationKeyMethod();
    }

    @Override
    public void setSipApplicationKeyMethod(Method sipApplicationKeyMethod) {
        this.deploymentInfoFacade.setSipApplicationKeyMethod(sipApplicationKeyMethod);

    }

    @Override
    public void setSipLoginConfig(MobicentsSipLoginConfig config) {
        this.deploymentInfoFacade.setSipLoginConfig(config);

    }

    @Override
    public MobicentsSipLoginConfig getSipLoginConfig() {
        return this.deploymentInfoFacade.getSipLoginConfig();
    }

    @Override
    public void addSipServletMapping(MobicentsSipServletMapping sipServletMapping) {
        this.deploymentInfoFacade.addSipServletMapping(sipServletMapping);

    }

    @Override
    public void removeSipServletMapping(MobicentsSipServletMapping sipServletMapping) {
        this.deploymentInfoFacade.removeSipServletMapping(sipServletMapping);

    }

    @Override
    public List<MobicentsSipServletMapping> findSipServletMappings() {
        return this.deploymentInfoFacade.findSipServletMappings();
    }

    @Override
    public MobicentsSipServletMapping findSipServletMappings(SipServletRequest sipServletRequest) {
        return this.deploymentInfoFacade.findSipServletMappings(sipServletRequest);
    }

    @Override
    public SipManager getSipManager() {
        return (SipManager) this.deployment.getSessionManager();
    }

    @Override
    public SipApplicationDispatcher getSipApplicationDispatcher() {
        return this.sipApplicationDispatcher;
    }

    @Override
    public String getEngineName() {
        // FIXME: kakonyii: i think this is not necessary for wildfy, have to review later
        return null;
    }

    // @Override
    // public String getBasePath() {
    // FIXME: kakonyii: this method was used in SipStandardContext's start() method to add missing components as necessary,
    // review later to figure out how to do something similar in wildfly
    // return null;
    // }

    // callers: SipApplicationDispatcher.addSipApplications()
    @Override
    public boolean notifySipContextListeners(SipContextEvent event) {
        boolean ok = true;
        if (logger.isDebugEnabled()) {
            logger.debug(this.deploymentInfoFacade.getSipServlets().size() + " container to notify of " + event.getEventType());
        }
        if (event.getEventType() == SipContextEventType.SERVLET_INITIALIZED) {
            //fixes https://github.com/RestComm/sip-servlets/issues/165
            //now the SipService is totally ready/started, we prepare 
            //the context again just in case some att was not properly
            //initiated
            try {
                prepareServletContext();
            } catch (Exception e) {
                logger.warn("Couldnt prepare context", e);
            }              
            if (!timerService.isStarted()) {
                timerService.start();
            }
            if (!proxyTimerService.isStarted()) {
                proxyTimerService.start();
            }
            if (!sasTimerService.isStarted()) {
                sasTimerService.start();
            }
        }

        // if(this.available) {
        enterSipApp(null, null, false, true);
        boolean batchStarted = enterSipAppHa(true);
        try {

            for (String servletName : this.deploymentInfoFacade.getSipServlets().keySet()) {
                SipServletImpl managedServlet = (SipServletImpl) this.getChildrenMap().get(servletName);
                if (logger.isDebugEnabled()) {
                    logger.debug("managedServlet " + managedServlet.getServletInfo().getName() + ", class : "
                            + managedServlet.getClass().getName());
                }
                try {
                    final ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();

                    try {
                        final ClassLoader cl = this.getSipContextClassLoader();
                        Thread.currentThread().setContextClassLoader(cl);
                        // http://code.google.com/p/sipservlets/issues/detail?id=135
                        // kakonyii: in SipStandardContext, there was a threadBindingListener which handled thread context switches (to get proper jndi context namespace selector, etc),
                        // in wildfly we use threadSetupAction from the deployment object for that purpose:
                        bindThreadBindingListener();

                        Servlet sipServlet = managedServlet.getServlet().getInstance();
                        if (sipServlet instanceof SipServlet) {
                            // Fix for issue 1086 (http://code.google.com/p/mobicents/issues/detail?id=1086) :
                            // Cannot send a request in SipServletListener.initialize() for servlet-selection applications
                            boolean servletHandlerWasNull = false;
                            if (this.getServletHandler() == null) {
                                this.setServletHandler(managedServlet.getServletInfo().getName());
                                servletHandlerWasNull = true;
                            }

                            switch (event.getEventType()) {
                                case SERVLET_INITIALIZED: {
                                    SipServletContextEvent sipServletContextEvent = new SipServletContextEvent(
                                            getServletContext(), (SipServlet) sipServlet);
                                    List<SipServletListener> sipServletListeners = sipListeners.getSipServletsListeners();
                                    if (logger.isDebugEnabled()) {
                                        logger.debug(sipServletListeners.size()
                                                + " SipServletListener to notify of servlet initialization");
                                    }
                                    for (SipServletListener sipServletListener : sipServletListeners) {
                                        sipServletListener.servletInitialized(sipServletContextEvent);
                                    }
                                    break;
                                }
                                case SIP_CONNECTOR_ADDED: {
                                    // reload the outbound interfaces if they have changed
                                    this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.OUTBOUND_INTERFACES,
                                            sipApplicationDispatcher.getOutboundInterfaces());
                                    // https://code.google.com/p/sipservlets/issues/detail?id=246
                                    this.getServletContext().setAttribute("org.mobicents.servlet.sip.SIP_CONNECTORS",
                                            sipApplicationDispatcher.getSipService().findSipConnectors());

                                    List<SipConnectorListener> sipConnectorListeners = sipListeners.getSipConnectorListeners();
                                    if (logger.isDebugEnabled()) {
                                        logger.debug(sipConnectorListeners.size()
                                                + " SipConnectorListener to notify of sip connector addition");
                                    }
                                    for (SipConnectorListener sipConnectorListener : sipConnectorListeners) {
                                        sipConnectorListener.sipConnectorAdded((SipConnector) event.getEventObject());
                                    }
                                    break;
                                }
                                case SIP_CONNECTOR_REMOVED: {
                                    // reload the outbound interfaces if they have changed
                                    this.getServletContext().setAttribute(javax.servlet.sip.SipServlet.OUTBOUND_INTERFACES,
                                            sipApplicationDispatcher.getOutboundInterfaces());
                                    // https://code.google.com/p/sipservlets/issues/detail?id=246
                                    this.getServletContext().setAttribute("org.mobicents.servlet.sip.SIP_CONNECTORS",
                                            sipApplicationDispatcher.getSipService().findSipConnectors());

                                    List<SipConnectorListener> sipConnectorListeners = sipListeners.getSipConnectorListeners();
                                    if (logger.isDebugEnabled()) {
                                        logger.debug(sipConnectorListeners.size()
                                                + " SipConnectorListener to notify of sip connector removal");
                                    }
                                    for (SipConnectorListener sipConnectorListener : sipConnectorListeners) {
                                        sipConnectorListener.sipConnectorRemoved((SipConnector) event.getEventObject());
                                    }
                                    break;
                                }
                            }
                            if (servletHandlerWasNull) {
                                this.setServletHandler("");
                            }
                        }
                    } finally {
                        // http://code.google.com/p/sipservlets/issues/detail?id=135
                        // kakonyii: in SipStandardContext, there was a threadBindingListener which handled thread context switches (to get proper jndi context namespace selector, etc),
                        // in wildfly we use threadSetupAction from the deployment object for that purpose:
                        unbindThreadBindingListener();
                        Thread.currentThread().setContextClassLoader(oldClassLoader);
                    }
                } catch (ServletException e) {
                    logger.error("Cannot allocate the servlet " + managedServlet.getClass() + " for notifying the listener "
                            + " of the event " + event.getEventType(), e);
                    ok = false;
                } catch (Throwable e) {
                    logger.error("An error occured when notifying the servlet " + managedServlet.getClass() + " of the event "
                            + event.getEventType(), e);
                    ok = false;
                }

                // FIXME: kakonyii: do we need this deallocation in wildfly?
                // try {
                // if(sipServlet != null) {
                // wrapper.deallocate(sipServlet);
                // }
                // } catch (ServletException e) {
                // logger.error("Deallocate exception for servlet" + wrapper.getName(), e);
                // ok = false;
                // } catch (Throwable e) {
                // logger.error("Deallocate exception for servlet" + wrapper.getName(), e);
                // ok = false;
                // }
            }
        } finally {
            exitSipAppHa(null, null, batchStarted);
            exitSipApp(null, null);
        }
        // }
        return ok;
    }

    @Override
    public void enterSipApp(MobicentsSipApplicationSession sipApplicationSession, MobicentsSipSession sipSession,
            boolean checkIsManagedThread, boolean isContainerManaged) {
        switch (this.getConcurrencyControlMode()) {
            case SipSession:
                if (sipSession != null) {
                    sipSession.acquire();
                }
                break;
            case SipApplicationSession:
                if (logger.isDebugEnabled()) {
                    logger.debug("checkIsManagedThread " + checkIsManagedThread + " , isManagedThread " + isManagedThread.get()
                            + ", isContainerManaged " + isContainerManaged);
                }
                // http://code.google.com/p/mobicents/issues/detail?id=2534 &&
                // http://code.google.com/p/mobicents/issues/detail?id=2526
                if (!checkIsManagedThread || (checkIsManagedThread && Boolean.TRUE.equals(isManagedThread.get()))) {
                    if (isManagedThread.get() == null) {
                        isManagedThread.set(Boolean.TRUE);
                    }
                    if (sipApplicationSession != null) {
                        SipApplicationSessionCreationThreadLocal sipApplicationSessionCreationThreadLocal = sipApplicationSessionsAccessedThreadLocal
                                .get();
                        if (sipApplicationSessionCreationThreadLocal == null) {
                            sipApplicationSessionCreationThreadLocal = new SipApplicationSessionCreationThreadLocal();
                            sipApplicationSessionsAccessedThreadLocal.set(sipApplicationSessionCreationThreadLocal);
                        }
                        boolean notPresent = sipApplicationSessionCreationThreadLocal.getSipApplicationSessions().add(
                                sipApplicationSession);
                        if (notPresent && isContainerManaged) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("acquiring sipApplicationSession=" + sipApplicationSession
                                        + " since it is not present in our local thread of accessed sip application sessions ");
                            }
                            sipApplicationSession.acquire();
                        } else if (logger.isDebugEnabled()) {
                            if (!isContainerManaged) {
                                logger.debug("not acquiring sipApplicationSession=" + sipApplicationSession
                                        + " since application specified the container shouldn't manage it ");
                            } else {
                                logger.debug("not acquiring sipApplicationSession=" + sipApplicationSession
                                        + " since it is present in our local thread of accessed sip application sessions ");
                            }
                        }
                    }
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("not acquiring sipApplicationSession=" + sipApplicationSession
                                + " since isManagedThread is " + isManagedThread.get());
                    }
                }
                break;
            case None:
                break;
        }
    }

    @Override
    public void exitSipApp(MobicentsSipApplicationSession sipApplicationSession, MobicentsSipSession sipSession) {
        switch (this.getConcurrencyControlMode()) {
            case SipSession:
                if (sipSession != null) {
                    sipSession.release();
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("NOT RELEASING SipSession on exit sipApplicationSession=" + sipApplicationSession
                                + " sipSession=" + sipSession + " semaphore=null");
                    }
                }
                break;
            case SipApplicationSession:
                boolean wasSessionReleased = false;
                SipApplicationSessionCreationThreadLocal sipApplicationSessionCreationThreadLocal = sipApplicationSessionsAccessedThreadLocal
                        .get();
                if (sipApplicationSessionCreationThreadLocal != null) {
                    for (MobicentsSipApplicationSession sipApplicationSessionAccessed : sipApplicationSessionsAccessedThreadLocal
                            .get().getSipApplicationSessions()) {
                        sipApplicationSessionAccessed.release();
                        if (sipApplicationSessionAccessed.equals(sipApplicationSession)) {
                            wasSessionReleased = true;
                        }
                    }
                    sipApplicationSessionsAccessedThreadLocal.get().getSipApplicationSessions().clear();
                    sipApplicationSessionsAccessedThreadLocal.set(null);
                    sipApplicationSessionsAccessedThreadLocal.remove();
                }
                isManagedThread.set(null);
                isManagedThread.remove();
                if (!wasSessionReleased) {
                    if (sipApplicationSession != null) {
                        sipApplicationSession.release();
                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.debug("NOT RELEASING SipApplicationSession on exit sipApplicationSession="
                                    + sipApplicationSession + " sipSession=" + sipSession + " semaphore=null");
                        }
                    }
                }
                break;
            case None:
                break;
        }

    }

    @Override
    public boolean enterSipAppHa(boolean startCacheActivity) {
        boolean batchStarted = false;
        return batchStarted;
    }

    @Override
    public void exitSipAppHa(MobicentsSipServletRequest request, MobicentsSipServletResponse response, boolean batchStarted) {
        // FIXME: distributable not supported
    }

    @Override
    public SipFactory getSipFactoryFacade() {
        return sipFactoryFacade;
    }

    @Override
    public MobicentsSipSessionsUtil getSipSessionsUtil() {
        return sipSessionsUtil;
    }

    @Override
    public TimerService getTimerService() {
        return timerService;
    }

    @Override
    public ProxyTimerService getProxyTimerService() {
        return proxyTimerService;
    }

    @Override
    public SipApplicationSessionTimerService getSipApplicationSessionTimerService() {

        return sasTimerService;
    }

    @Override
    public void setConcurrencyControlMode(ConcurrencyControlMode mode) {
        this.deploymentInfoFacade.setConcurrencyControlMode(mode);
        if (this.deploymentInfoFacade.getConcurrencyControlMode() != null && logger.isDebugEnabled()) {
            logger.debug("Concurrency Control set to " + this.deploymentInfoFacade.getConcurrencyControlMode().toString()
                    + " for application " + this.deploymentInfoFacade.getApplicationName());
        }
    }

    @Override
    public ConcurrencyControlMode getConcurrencyControlMode() {
        return this.deploymentInfoFacade.getConcurrencyControlMode();
    }

    @Override
    public void setSipRubyController(SipRubyController rubyController) {
        this.deploymentInfoFacade.setRubyController(rubyController);

    }

    @Override
    public SipRubyController getSipRubyController() {
        return this.deploymentInfoFacade.getRubyController();
    }

    @Override
    public String getPath() {
        // kakonyii: currrently we use this method at ConvergedSessionDelegate encodeURL()
        return this.deployment.getServletContext().getContextPath();
        //return this.deploymentInfoFacade.getDeploymentInfo().getContextPath();
    }

    @Override
    public MobicentsSipServlet findSipServletByClassName(String canonicalName) {
        if (canonicalName == null)
            return (null);
        return this.deploymentInfoFacade.getChildrenMapByClassName().get(canonicalName);
    }

    @Override
    public MobicentsSipServlet findSipServletByName(String name) {
        if (name == null)
            return (null);
        return this.deploymentInfoFacade.getChildrenMap().get(name);
    }

    @Override
    public ClassLoader getSipContextClassLoader() {
        return sipContextClassLoader;
    }

    @Override
    public Map<String, MobicentsSipServlet> getChildrenMap() {
        return this.deploymentInfoFacade.getChildrenMap();
    }

    @Override
    public boolean isPackageProtectionEnabled() {
        // Got from org.apache.catalina.security.SecurityUtil:
        boolean packageDefinitionEnabled = (System.getProperty("package.definition") == null && System
                .getProperty("package.access") == null) ? false : true;
        boolean isSecurityEnabled = (System.getSecurityManager() != null);

        if (packageDefinitionEnabled && isSecurityEnabled) {
            return true;
        }
        return false;
    }

    @Override
    public boolean authorize(MobicentsSipServletRequest request) {
        String servletInfoName = request.getSipSession().getHandler();
        ServletInfo servletInfo = this.getDeploymentInfoFacade().getSipServlets().get(servletInfoName);

        return sipSecurityUtils.authorize(request, servletInfo, this.getSipApplicationDispatcher().getSipStack());

    }

    @Override
    public SipDigestAuthenticator getDigestAuthenticator() {
        return this.sipDigestAuthenticator;
    }

    private Handle handle;
    public void bindThreadBindingListener() {
        handle=getDeployment().getThreadSetupAction().setup(null);
    }
    public void unbindThreadBindingListener() {
        handle.tearDown();
    }

    @Override
    public void enterSipContext() {
        final ClassLoader cl = getSipContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);
        // http://code.google.com/p/sipservlets/issues/detail?id=135
        // kakonyii: in SipStandardContext, there was a threadBindingListener which handled thread context switches (to get proper jndi context namespace selector, etc),
        // in wildfly we use threadSetupAction from the deployment object for that purpose:
        bindThreadBindingListener();
    }

    @Override
    public void exitSipContext(ClassLoader oldClassLoader) {
        // http://code.google.com/p/sipservlets/issues/detail?id=135
        // kakonyii: in SipStandardContext, there was a threadBindingListener which handled thread context switches (to get proper jndi context namespace selector, etc),
        // in wildfly we use threadSetupAction from the deployment object for that purpose:
        unbindThreadBindingListener();
        Thread.currentThread().setContextClassLoader(oldClassLoader);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public DeploymentInfoFacade getDeploymentInfoFacade() {
        return deploymentInfoFacade;
    }

    public Deployment getDeployment() {
        return deployment;
    }

    public String getSecurityDomain() {
        return securityDomain;
    }

    public void setSecurityDomain(String securityDomain) {
        this.securityDomain = securityDomain;
    }

    public Host getHostOfDeployment() {
        return hostOfDeployment;
    }

    public void setHostOfDeployment(Host hostOfDeployment) {
        this.hostOfDeployment = hostOfDeployment;
    }

    public List<ListenerService<?>> getWebServerListeners() {
        return webServerListeners;
    }

    public void setWebServerListeners(List<ListenerService<?>> webServerListeners) {
        this.webServerListeners = webServerListeners;
    }

    public enum TimerServiceType{
        STANDARD,
        DEFAULT;
    }

    @Override
	public void stopGracefully(long timeToWait) {
		// http://code.google.com/p/sipservlets/issues/detail?id=195 
		// Support for Graceful Shutdown of SIP Applications and Overall Server
		if(logger.isInfoEnabled()) {
			logger.info("Stopping the Context " + getApplicationName() + " Gracefully in " + timeToWait + " ms");
		}
		// Guarantees that the application won't be routed any initial requests anymore but will still handle subsequent requests
		List<String> applicationsUndeployed = new ArrayList<String>();
		applicationsUndeployed.add(getApplicationName());
		sipApplicationDispatcher.getSipApplicationRouter().applicationUndeployed(applicationsUndeployed);
		if(timeToWait == 0) {
			// equivalent to forceful stop
			if(gracefulStopFuture != null) {
				gracefulStopFuture.cancel(false);
			}
			try {
				stop();
			} catch (ServletException e) {
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

    @Override
    public void setGracefulInterval(long arg0) {
        // TODO Auto-generated method stub
        logger.error(new MethodNotSupportedException("Method setGracefulInterval(long) was not implemented yet"));
    }

}
