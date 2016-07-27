/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2016, Telestax Inc and individual contributors
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

package org.jboss.as.web.session.sip;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.catalina.valves.ValveBase;
import org.jboss.as.clustering.web.OutgoingDistributableSessionData;
import org.jboss.as.web.session.ClusteredSession;
import org.jboss.as.web.session.SessionManager;
import org.jboss.logging.Logger;
import org.mobicents.servlet.sip.catalina.session.ConvergedSessionFacade;
import org.mobicents.servlet.sip.core.session.MobicentsSipApplicationSession;
import org.mobicents.servlet.sip.startup.StaticServiceHolder;

/**
 * Web request valve to specifically handle Tomcat jvmRoute using mod_jk(2)
 * module. We assume that the session is set by cookie only for now, i.e., no
 * support of that from URL. Furthermore, the session id has a format of
 * id.jvmRoute where jvmRoute is used by JK module to determine sticky session
 * during load balancing.
 *
 * @author Ben Wang
 * @author vralev
 * @version $Revision: 59035 $
 * @author posfai.gergely@ext.alerant.hu
 */
public class ConvergedJvmRouteValve extends ValveBase implements Lifecycle
{
	// The info string for this Valve
	private static final String info = "JvmRouteValve/1.0";

	protected static Logger log_ = Logger.getLogger(ConvergedJvmRouteValve.class);

	// Valve-lifecycle_ helper object
	protected LifecycleSupport support = new LifecycleSupport(this);

	protected SessionManager manager_;

	/**
	 * Create a new Valve.
	 *
	 */
	public ConvergedJvmRouteValve(SessionManager manager)
	{
		super();
		manager_ = manager;
	}

	/**
	 * Get information about this Valve.
	 */
	public String getInfo()
	{
		return info;
	}

	public void invoke(Request request, Response response) throws IOException, ServletException
	{


		// Need to check it before let it through. This is ok because this 
		// valve is inserted only when mod_jk option is configured.
		checkJvmRoute(request, response);

		// If we don't refresh the page due to failover/switchover
		if(response.getHeader("Mobicents-Refresh") == null) {
			// let the servlet invokation go through
			getNext().invoke(request, response);
		}
	}

	public void checkJvmRoute(Request req, Response res)
	throws IOException, ServletException
	{
		String oldsessionId = req.getRequestedSessionId();
		HttpSession session = req.getSession(false);
		if (session != null)
		{
			String sessionId = session.getId();

			// Obtain JvmRoute
			String jvmRoute = manager_.getJvmRoute();
			if (log_.isDebugEnabled())
			{
				log_.debug("checkJvmRoute(): check if need to re-route based on JvmRoute. Session id: " +
						sessionId + " jvmRoute: " + jvmRoute);
			}

			if (jvmRoute == null)
			{
				throw new RuntimeException("JvmRouteValve.checkJvmRoute(): Tomcat JvmRoute is null. " +
				"Need to assign a value in Tomcat server.xml for load balancing.");
			}

			// Check if incoming session id has JvmRoute appended. If not, append it.
			boolean setCookie = !req.isRequestedSessionIdFromURL();
			handleJvmRoute(oldsessionId, sessionId, jvmRoute, res, setCookie, session, req);
		}
	}

	protected void handleJvmRoute(String oldsessionId,
			String sessionId, 
			String jvmRoute, 
			HttpServletResponse response,
			boolean setCookie, HttpSession session, Request request)
	{
		int indexReceivedJvmRoute;
		// Get requested jvmRoute.
		// TODO. The current format is assumed to be id.jvmRoute. Can be generalized later.
		String receivedJvmRoute = null;
		if (oldsessionId != null)
		{
			indexReceivedJvmRoute = sessionId.indexOf('.', 0);
			if (indexReceivedJvmRoute > -1 && indexReceivedJvmRoute < sessionId.length() -1)
			{
				receivedJvmRoute = sessionId.substring(indexReceivedJvmRoute + 1, sessionId.length());
			}
		}

		int indexRequestedJvmRoute;
		String requestedJvmRoute = null;
		indexRequestedJvmRoute = sessionId.indexOf('.', 0);
		if (indexRequestedJvmRoute > -1 && indexRequestedJvmRoute < sessionId.length() -1)
		{
			requestedJvmRoute = sessionId.substring(indexRequestedJvmRoute + 1, sessionId.length());
		}

		String newId = null;
		if (!jvmRoute.equals(requestedJvmRoute))
		{
			if(StaticServiceHolder.sipStandardService.isHttpFollowsSip()) {
				if(session instanceof ConvergedSessionFacade) {
					boolean correctionAlreadyAttempted = false;
					for(Cookie cookie:request.getCookies()) {
						if(cookie.getName().equals("org.mobicents.servlet.sip.CorrectionAttemptedFlag")) {
							correctionAlreadyAttempted = cookie.getValue().equals("true");
						}
					}
					if(log_.isDebugEnabled()) {
						log_.debug("Correction already attempted is " + correctionAlreadyAttempted
								+ " for http session " + sessionId);
					}
					ConvergedSessionFacade sessionFacade = (ConvergedSessionFacade) session;
					MobicentsSipApplicationSession appSession = sessionFacade.getApplicationSession(false);

					if(correctionAlreadyAttempted) {
						// If we already tried then just give up and assign the session to the current node
						if(appSession != null) {
							if(log_.isDebugEnabled()) {
								log_.debug("Failover, but we couldn't recover. We will continue on the current node." +
										" Probably this node is dead " + appSession.getJvmRoute());
							}
							appSession.setJvmRoute(jvmRoute);
							Cookie unsetCookie = new Cookie("org.mobicents.servlet.sip.CorrectionAttemptedFlag", "");
							unsetCookie.setMaxAge(0);
							response.addCookie(unsetCookie);
						}
					} else {
						if(appSession != null) {
							String appSessionJvmRoute = appSession.getJvmRoute();
							if(log_.isDebugEnabled()) {
								log_.debug("HTTP session=" + sessionId + " is associated with app session=" + appSession.getId());
								log_.debug("App Session jvmRoute is " + appSessionJvmRoute + " for http session " + sessionId);
							}
							if(appSessionJvmRoute != null) {
								if (indexRequestedJvmRoute < 0)
								{
									// If this valve is turned on, we assume we have an appendix of jvmRoute. 
									// So this request is new.
									newId = new StringBuilder(sessionId).append('.').append(appSessionJvmRoute).toString();
								}         
								else 
								{
									// We just had a failover since jvmRoute does not match. 
									// We will replace the old one with the new one.
									String base = sessionId.substring(0, indexRequestedJvmRoute);
									newId = base + "." + appSessionJvmRoute;
								}
								response.addHeader("Refresh", "1"); // This way we will reload the page in 1 sec
								response.addHeader("Mobicents-Refresh", "true"); // flag to be monitored with Wireshark

								resetSessionId(sessionId, newId);

								Cookie correctionAttemptedFlag = new Cookie("org.mobicents.servlet.sip.CorrectionAttemptedFlag", "true");
								response.addCookie(correctionAttemptedFlag);
								
								if(log_.isDebugEnabled()) {
									log_.debug("Setting the correctionAttempted flag to true for session " + sessionId);
								}
								try {
									response.getOutputStream().close();
								} catch (IOException e) {
									log_.error("Error sending refresh", e);
								}
								return;
							}
						}
					}
				}
			}
			if (indexRequestedJvmRoute < 0)
			{
				// If this valve is turned on, we assume we have an appendix of jvmRoute. 
				// So this request is new.
				newId = new StringBuilder(sessionId).append('.').append(jvmRoute).toString();
			}         
			else 
			{
				// We just had a failover since jvmRoute does not match. 
				// We will replace the old one with the new one.         
				if (log_.isInfoEnabled())
				{
					log_.info("handleJvmRoute(): We have detected a failover with different jvmRoute." +
							" old one: " + requestedJvmRoute + " new one: " + jvmRoute + ". Will reset the session id.");
				}

				String base = sessionId.substring(0, indexRequestedJvmRoute);
				newId = base + "." + jvmRoute;

				if(!StaticServiceHolder.sipStandardService.isHttpFollowsSip()) {
					if(session instanceof ConvergedSessionFacade) {
						ConvergedSessionFacade sessionFacade = (ConvergedSessionFacade) session;

						// Change the jvmRoute in case of failover
						sessionFacade.getApplicationSession(true).setJvmRoute(jvmRoute);
						StaticServiceHolder.sipStandardService.getSipApplicationDispatcher().
						sendSwitchoverInstruction(requestedJvmRoute, jvmRoute);    				
					}
				}
			}

			resetSessionId(sessionId, newId);

		} else {
			log_.debug("No failover. We should unset the cookie");
			Cookie unsetCookie = new Cookie("org.mobicents.servlet.sip.CorrectionAttemptedFlag", "");
			unsetCookie.setMaxAge(0);
			response.addCookie(unsetCookie);
		}
		/* Also check the jvmRoute received (via req.getRequestedSessionId()) */
		if (!jvmRoute.equals(receivedJvmRoute))
		{
			if (log_.isDebugEnabled())
			{
				log_.debug("handleJvmRoute(): We have detected a failover with different jvmRoute." +
						" received one: " + receivedJvmRoute + " new one: " + jvmRoute + ". Will resent the session id.");
			}
			String base = sessionId.substring(0, indexRequestedJvmRoute);
			newId = base + "." + jvmRoute;
		}

		/* Change the sessionid cookie if needed */
		if (setCookie && newId != null)
			manager_.setNewSessionCookie(newId, response);
	}

	private void resetSessionId(String oldId, String newId)
	{
		try
		{
			@SuppressWarnings("unchecked")
			ClusteredSession<? extends OutgoingDistributableSessionData> session = (ClusteredSession) manager_.findSession(oldId);
			// change session id with the new one using local jvmRoute.
			if( session != null )
			{
				// Note this will trigger a session remove from the super Tomcat class.
				session.resetIdWithRouteInfo(newId);
				if (log_.isDebugEnabled())
				{
					log_.debug("resetSessionId(): changed catalina session to= [" + newId + "] old one= [" + oldId + "]");
				}
			}
			else if (log_.isDebugEnabled())
			{
				log_.debug("resetSessionId(): no session with id " + newId + " found");
			}
		}
		catch (IOException e)
		{
			if (log_.isDebugEnabled())
			{
				log_.debug("resetSessionId(): manager_.findSession() unable to find session= [" + oldId + "]", e);
			}
			throw new RuntimeException("JvmRouteValve.resetSessionId(): cannot find session [" + oldId + "]", e);
		}
	}

	// Lifecycle Interface
	public void addLifecycleListener(LifecycleListener listener)
	{
		support.addLifecycleListener(listener);
	}

	public void removeLifecycleListener(LifecycleListener listener)
	{
		support.removeLifecycleListener(listener);
	}

	public LifecycleListener[] findLifecycleListeners()
	{
		return support.findLifecycleListeners();
	}

	public void start() throws LifecycleException
	{
		support.fireLifecycleEvent(START_EVENT, this);
	}

	public void stop() throws LifecycleException
	{
		support.fireLifecycleEvent(STOP_EVENT, this);
	}

}
