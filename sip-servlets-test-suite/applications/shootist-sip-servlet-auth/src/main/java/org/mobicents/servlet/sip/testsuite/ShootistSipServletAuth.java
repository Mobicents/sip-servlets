/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2013, Telestax Inc and individual contributors
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

package org.mobicents.servlet.sip.testsuite;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.sip.AuthInfo;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionEvent;
import javax.servlet.sip.SipSessionListener;
import javax.servlet.sip.SipURI;
import javax.sip.ListeningPoint;
import javax.sip.header.ProxyAuthenticateHeader;

import org.apache.log4j.Logger;
import org.mobicents.javax.servlet.sip.SipServletRequestExt;

public class ShootistSipServletAuth 
		extends SipServlet 
		implements SipServletListener, SipSessionListener {
	private static final long serialVersionUID = 1L;
	private static transient Logger logger = Logger.getLogger(ShootistSipServletAuth.class);
	private static final String CONTENT_TYPE = "text/plain;charset=UTF-8";
	
	private String sampleSDP = "v=0" +
      "o=alice 2890844526 2890844526 IN IP4 host.atlanta.example.com" +
      "s=" +
      "c=IN IP4 host.atlanta.example.com" +
      "t=0 0" +
      "m=audio 49170 RTP/AVP 0" +
      "a=rtpmap:0 PCMU/8000" +
      "m=audio 51372 RTP/AVP 97 101" +
      "a=rtpmap:97 iLBC/8000" +
      "a=rtpmap:101 telephone-event/8000";
	
	private String sampleSDP2 = "v=1" +
		      "o=alice 2890844526 2890844526 IN IP4 host.atlanta.example.com" +
		      "s=" +
		      "c=IN IP4 host.atlanta.example.com" +
		      "t=0 0" +
		      "m=audio 49170 RTP/AVP 0" +
		      "a=rtpmap:0 PCMU/8000" +
		      "m=audio 51372 RTP/AVP 97 101" +
		      "a=rtpmap:97 iLBC/8000" +
		      "a=rtpmap:101 telephone-event/8000";
	
	/** Creates a new instance of ShootistSipServletAuth */
	public ShootistSipServletAuth() {
	}

	@Override
	public void init(ServletConfig servletConfig) throws ServletException {
		logger.info("the shootist has been started");
		super.init(servletConfig);
	}		
	
	@Override
	protected void doErrorResponse(SipServletResponse response)
			throws ServletException, IOException {
			
		SipFactory sipFactory = (SipFactory) getServletContext().getAttribute(SIP_FACTORY);
		if(response.getStatus() == SipServletResponse.SC_UNAUTHORIZED || 
				response.getStatus() == SipServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED) {
		
			// Avoid re-sending if the auth repeatedly fails.
			if(!"true".equals(getServletContext().getAttribute("FirstResponseRecieved")))
			{
				String fromString = response.getFrom().getURI().toString();
				
				getServletContext().setAttribute("FirstResponseRecieved", "true");
				// non regression test for https://code.google.com/p/sipservlets/issues/detail?id=239
				Parameterable proxyAuthHeader = response.getParameterableHeader(ProxyAuthenticateHeader.NAME);
				AuthInfo authInfo = sipFactory.createAuthInfo();
				String realm = proxyAuthHeader.getParameter("Digest realm");
				authInfo.addAuthInfo(response.getStatus(), realm, "user", "pass");
				SipServletRequest challengeRequest = response.getSession().createRequest(
						response.getRequest().getMethod());
				boolean cacheCredentials = false;
				if(fromString.contains("cache-credentials")) {
					cacheCredentials = true;
				}
				logger.info("cache Credentials : " + cacheCredentials);	
				((SipServletRequestExt)challengeRequest).addAuthHeader(response, authInfo, cacheCredentials);
				challengeRequest.send();
				
				if(fromString.contains("cancelChallenge")) {
					if(fromString.contains("Before1xx")) {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {				
							logger.error("unexpected exception", e);
						}
						challengeRequest.createCancel().send();
					} else {
						response.getSession().setAttribute("cancelChallenge", challengeRequest);
					}
				}
			}			
		}
		
		logger.info("Got response: " + response);	
		
	}
	
	@Override
	protected void doProvisionalResponse(SipServletResponse resp)
			throws ServletException, IOException {
		SipServletRequest servletRequest = (SipServletRequest) resp.getSession().getAttribute("cancelChallenge");
		if(servletRequest != null && resp.getStatus() > 100) {
			servletRequest.createCancel().send();
		}
	}
	
	@Override
	protected void doSuccessResponse(SipServletResponse sipServletResponse)
			throws ServletException, IOException {
		logger.info("Got : " + sipServletResponse.getStatus() + " "
				+ sipServletResponse.getMethod());
		int status = sipServletResponse.getStatus();
		String fromString = sipServletResponse.getFrom().getURI().toString();
		if (status == SipServletResponse.SC_OK && "INVITE".equalsIgnoreCase(sipServletResponse.getMethod())) {
			SipServletRequest ackRequest = sipServletResponse.createAck();
			ackRequest.send();
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {				
				logger.error("unexpected exception", e);
			}
			if(!fromString.contains("reinvite")) {
				SipServletRequest sipServletRequest = sipServletResponse.getSession().createRequest("BYE");
				sipServletRequest.send();
			} else if(!sipServletResponse.getHeader("CSeq").contains((String)sipServletResponse.getApplicationSession().getAttribute("nbSubsequentReq"))) {
				getServletContext().setAttribute("FirstResponseRecieved", "false");
				SipServletRequest invite = sipServletResponse.getSession().createRequest(sipServletResponse.getMethod());
				try {
					invite.setContent(sampleSDP2, "application/sdp");
				} catch (UnsupportedEncodingException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				invite.send();
			}
		}
		if("REGISTER".equalsIgnoreCase(sipServletResponse.getMethod())) {
			try {
				Thread.sleep(4000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if(!sipServletResponse.getHeader("CSeq").contains((String)sipServletResponse.getApplicationSession().getAttribute("nbSubsequentReq"))) {
				getServletContext().setAttribute("FirstResponseRecieved", "false");
				SipServletRequest register = sipServletResponse.getSession().createRequest(sipServletResponse.getMethod());
				register.send();
			}
		}
	}
	
	@Override
	protected void doInfo(SipServletRequest req) throws ServletException,
			IOException {
		req.createResponse(200).send();
		SipServletRequest reInvite = req.getSession().createRequest("INVITE");
		reInvite.send();
		
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {				
			logger.error("unexpected exception", e);
		}
		
		reInvite.createCancel().send();
	}

	// SipServletListener methods
	/*
	 * (non-Javadoc)
	 * @see javax.servlet.sip.SipServletListener#servletInitialized(javax.servlet.sip.SipServletContextEvent)
	 */
	public void servletInitialized(SipServletContextEvent ce) {
		SipFactory sipFactory = (SipFactory)ce.getServletContext().getAttribute(SIP_FACTORY);
		String method = ce.getServletContext().getInitParameter("METHOD");
		if(method == null) {
			method = "INVITE";
		}
		
		SipApplicationSession sipApplicationSession = sipFactory.createApplicationSession();
		String from = ce.getServletContext().getInitParameter("from");
		if(from == null) {
			from = "BigGuy";
		}
		
		String numberOfSubsequentRequests = ce.getServletContext().getInitParameter("nbSubsequentReq");
		if(numberOfSubsequentRequests == null) {
			numberOfSubsequentRequests = "10";
		}
		SipURI fromURI = sipFactory.createSipURI(from, "here.com", null);			
		SipURI toURI = sipFactory.createSipURI("LittleGuy", "there.com", null);
		SipServletRequest sipServletRequest = 
			sipFactory.createRequest(sipApplicationSession, method, fromURI, toURI);
		sipApplicationSession.setAttribute("nbSubsequentReq", numberOfSubsequentRequests);
		SipURI requestURI = sipFactory.createSipURI("LittleGuy", "" + System.getProperty("org.mobicents.testsuite.testhostaddr") + ":5080", null);
		sipServletRequest.setRequestURI(requestURI);
		if(method.equalsIgnoreCase("INVITE")) {
			try {
				sipServletRequest.setContent(sampleSDP, "application/sdp");
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		try {			
			sipServletRequest.send();
		} catch (IOException e) {
			logger.error(e);
		}		
	}

	public void sessionCreated(SipSessionEvent se) {
		final SipSession sipSession = se.getSession();		
		Integer nbSessionCreated = (Integer) sipSession.getAttribute("nbSessionCreated");
		if(nbSessionCreated == null) {
			sipSession.setAttribute("nbSessionCreated", Integer.valueOf(1));
		} else {
			sipSession.setAttribute("nbSessionCreated", Integer.valueOf(nbSessionCreated.intValue() + 1));
		}
		logger.info("number of sip sessions created " + sipSession.getAttribute("nbSessionCreated") + " session " + sipSession);
		SipFactory sipFactory = (SipFactory)getServletContext().getAttribute(SIP_FACTORY);
		if(nbSessionCreated != null && nbSessionCreated > 1) {
			sendMessage(sipSession.getApplicationSession(), sipFactory, "" + nbSessionCreated, null);
		}
	}
	
	/**
	 * @param sipApplicationSession
	 * @param storedFactory
	 */
	private void sendMessage(SipApplicationSession sipApplicationSession,
			SipFactory storedFactory, String content, String transport) {
		try {
			SipServletRequest sipServletRequest = storedFactory.createRequest(
					sipApplicationSession, 
					"MESSAGE", 
					"sip:sender@sip-servlets.com", 
					"sip:receiver@sip-servlets.com");
			sipServletRequest.addHeader("Ext", "Test 1, 2 ,3");
			SipURI sipUri = storedFactory.createSipURI("receiver", "" + System.getProperty("org.mobicents.testsuite.testhostaddr") + ":5080", null);
			if(transport != null) {
				if(transport.equalsIgnoreCase(ListeningPoint.TCP)) {
					sipUri = storedFactory.createSipURI("receiver", "" + System.getProperty("org.mobicents.testsuite.testhostaddr") + ":5081", null);
				}
				sipUri.setTransportParam(transport);
			}
			sipServletRequest.setRequestURI(sipUri);
			sipServletRequest.setContentLength(content.length());
			sipServletRequest.setContent(content, CONTENT_TYPE);
			sipServletRequest.send();
		} catch (ServletParseException e) {
			logger.error("Exception occured while parsing the addresses",e);
		} catch (IOException e) {
			logger.error("Exception occured while sending the request",e);			
		}
	}

	public void sessionDestroyed(SipSessionEvent se) {
		// TODO Auto-generated method stub
		
	}

	public void sessionReadyToInvalidate(SipSessionEvent se) {
		// TODO Auto-generated method stub
		
	}
}