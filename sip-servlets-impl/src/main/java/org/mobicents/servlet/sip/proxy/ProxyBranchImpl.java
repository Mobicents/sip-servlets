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

package org.mobicents.servlet.sip.proxy;

import gov.nist.javax.sip.TransactionExt;
import gov.nist.javax.sip.header.Via;
import gov.nist.javax.sip.message.MessageExt;
import gov.nist.javax.sip.stack.SIPClientTransaction;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.ProxyBranch;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession.State;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;
import javax.sip.ClientTransaction;
import javax.sip.SipException;
import javax.sip.SipProvider;
import javax.sip.header.RouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.log4j.Logger;
import org.mobicents.javax.servlet.sip.ProxyBranchListener;
import org.mobicents.javax.servlet.sip.ResponseType;
import org.mobicents.servlet.sip.JainSipUtils;
import org.mobicents.servlet.sip.SipConnector;
import org.mobicents.servlet.sip.address.AddressImpl.ModifiableRule;
import org.mobicents.servlet.sip.address.SipURIImpl;
import org.mobicents.servlet.sip.core.DispatcherException;
import org.mobicents.servlet.sip.core.RoutingState;
import org.mobicents.servlet.sip.core.SipApplicationDispatcher;
import org.mobicents.servlet.sip.core.SipNetworkInterfaceManager;
import org.mobicents.servlet.sip.core.message.MobicentsSipServletRequest;
import org.mobicents.servlet.sip.core.message.MobicentsSipServletResponse;
import org.mobicents.servlet.sip.core.proxy.MobicentsProxyBranch;
import org.mobicents.servlet.sip.core.session.MobicentsSipApplicationSession;
import org.mobicents.servlet.sip.core.session.MobicentsSipSession;
import org.mobicents.servlet.sip.message.SipFactoryImpl;
import org.mobicents.servlet.sip.message.SipServletMessageImpl;
import org.mobicents.servlet.sip.message.SipServletRequestImpl;
import org.mobicents.servlet.sip.message.SipServletResponseImpl;
import org.mobicents.servlet.sip.message.TransactionApplicationData;
import org.mobicents.servlet.sip.rfc5626.IncorrectFlowIdentifierException;
import org.mobicents.servlet.sip.rfc5626.RFC5626Helper;
import org.mobicents.servlet.sip.startup.StaticServiceHolder;

/**
 * @author jean.deruelle@telestax.com
 * @author vralev@gmail.com
 *
 */
public class ProxyBranchImpl implements MobicentsProxyBranch, Externalizable {

	private static final String DEFAULT_RECORD_ROUTE_URI = "sip:proxy@localhost";
	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(ProxyBranchImpl.class);
	private transient ProxyImpl proxy;
	private transient SipServletRequestImpl originalRequest;
	private transient SipServletRequestImpl prackOriginalRequest;
	// From javadoc : object representing the request that is or to be proxied.
	private transient SipServletRequestImpl outgoingRequest;
	private transient SipServletResponseImpl lastResponse;
	// https://telestax.atlassian.net/browse/MSS-153 moving to String to optimize memory usage
	private String targetURI;
	private transient SipURI outboundInterface;
	// https://telestax.atlassian.net/browse/MSS-153 moving to String to optimize memory usage
	private transient String recordRouteURIString;
	// https://telestax.atlassian.net/browse/MSS-153 need to keep it as URI for the time of the transaction as
	// com.bea.sipservlet.tck.agents.spec.ProxyBranchTest.testAddNoSysHeader adds parameters to it that need to be passed on the outgoing invite
	private transient SipURI recordRouteURI;
	private boolean recordRoutingEnabled;
	// https://github.com/Mobicents/sip-servlets/issues/63
	private boolean appSpecifiedRecordRoutingEnabled = false;
	private boolean recurse;
	private transient SipURI pathURI;
	private boolean started;
	private boolean timedOut;
	private int proxyBranchTimeout;
	private int proxyBranch1xxTimeout;
	private transient ProxyBranchTimerTask proxyTimeoutTask;
	private transient ProxyBranchTimerTask proxy1xxTimeoutTask;
	private transient boolean proxyBranchTimerStarted;
	private transient boolean proxyBranch1xxTimerStarted;
	private transient Object cTimerLock;
	private boolean canceled;
	private boolean isAddToPath;
	private transient List<ProxyBranch> recursedBranches;
	private boolean waitingForPrack;
	// https://telestax.atlassian.net/browse/MSS-153 not needing to store it
//	public transient ViaHeader viaHeader;
	
	/*
	 * It is best to use a linked list here because we expect more than one tx only very rarely.
	 * Hashmaps, sets, etc allocate some buffers, making them have much bigger mem footprint.
	 * This list is kept because we need to support concurrent INVITE and other transactions happening
	 * in both directions in proxy. http://code.google.com/p/mobicents/issues/detail?id=1852
	 * 
	 */
	public transient List<TransactionRequest> ongoingTransactions = new LinkedList<TransactionRequest>();
	
	public static class TransactionRequest {
		public TransactionRequest(String branch, SipServletRequestImpl request) {
			this.branchId = branch;
			this.request = request;
		}
		public String branchId;
		public SipServletRequestImpl request;
	}
	
	// empty constructor used only for Externalizable interface
	public ProxyBranchImpl() {
		
		if(logger.isDebugEnabled()) {
			logger.debug("ProxyBranchImpl no-argument constructor");
		}
		
	}
	
	public ProxyBranchImpl(URI uri, ProxyImpl proxy)
	{
		if(logger.isDebugEnabled()) {
			logger.debug("ProxyBranchImpl constructor - proxy=" + proxy);
		}
		this.targetURI = uri.toString();
		this.proxy = proxy;
		isAddToPath = proxy.getAddToPath();
		this.originalRequest = (SipServletRequestImpl) proxy.getOriginalRequest();
		if(proxy.recordRouteURI != null) {
			this.recordRouteURI = proxy.recordRouteURI;
		}
		if(proxy.recordRouteURIString != null) {
			this.recordRouteURIString = proxy.recordRouteURIString;
		}
		this.pathURI = proxy.pathURI;
		this.outboundInterface = proxy.getOutboundInterface();
//		if(recordRouteURI != null) {
//			this.recordRouteURI = (SipURI)((SipURIImpl)recordRouteURI).clone();			
//		}
		this.proxyBranchTimeout = proxy.getProxyTimeout();
		this.proxyBranch1xxTimeout = proxy.getProxy1xxTimeout();
		this.canceled = false;
		this.recursedBranches = null;
		proxyBranchTimerStarted = false;
		cTimerLock = new Object();
		
		// Here we create a clone which is available through getRequest(), the user can add
		// custom headers and push routes here. Later when we actually proxy the request we
		// will clone this request (with it's custome headers and routes), but we will override
		// the modified RR and Path parameters (as defined in the spec).
		Request cloned = (Request)originalRequest.getMessage().clone();
		((MessageExt)cloned).setApplicationData(null);
		this.outgoingRequest = (SipServletRequestImpl) proxy.getSipFactoryImpl().getMobicentsSipServletMessageFactory().createSipServletRequest(
				cloned,
				this.originalRequest.getSipSession(),
				null, null, false);
	}
	
	/* (non-Javadoc)
	 * @see javax.servlet.sip.ProxyBranch#cancel()
	 */
	public void cancel() {		
		cancel(null, null, null);
	}

	/*
	 * (non-Javadoc)
	 * @see javax.servlet.sip.ProxyBranch#cancel(java.lang.String[], int[], java.lang.String[])
	 */
	public void cancel(String[] protocol, int[] reasonCode, String[] reasonText) {
		if(proxy.getAckReceived()) throw new IllegalStateException("There has been an ACK received on this branch. Can not cancel.");
		
		try {			
			cancelTimer();
			if(this.isStarted() && !canceled && !timedOut &&
					(outgoingRequest.getMethod().equalsIgnoreCase(Request.INVITE) ||
							// https://code.google.com/p/sipservlets/issues/detail?id=253
							outgoingRequest.getMethod().equalsIgnoreCase(Request.PRACK) ||
							// https://code.google.com/p/sipservlets/issues/detail?id=33
							outgoingRequest.getMethod().equalsIgnoreCase(Request.UPDATE))) {
				if(lastResponse != null) { /* According to SIP RFC we should send cancel only if we receive any response first*/
					if(logger.isDebugEnabled()) {
						logger.debug("Trying to cancel ProxyBranch for outgoing request " + outgoingRequest);
					}
					if(lastResponse.getStatus() > Response.OK && recursedBranches != null && !recursedBranches.isEmpty()) {
						//  Javadoc says it should throw an java.lang.IllegalStateException if the transaction has already been completed and it has no child branches
						if(logger.isDebugEnabled()) {
							logger.debug("lastResponse status for this branch is " + lastResponse.getStatus() + " and it has " + recursedBranches.size() + " to cancel");
						}
						return;
					}
					SipServletRequest cancelRequest = null;
					if(outgoingRequest.getMethod().equalsIgnoreCase(Request.PRACK) || outgoingRequest.getMethod().equalsIgnoreCase(Request.UPDATE)) {
						// https://code.google.com/p/sipservlets/issues/detail?id=253 and https://code.google.com/p/sipservlets/issues/detail?id=33
						// in case of PRACK or UPDATE we need to take the original INVITE
						cancelRequest = originalRequest.getLinkedRequest().createCancel();
					} else {
						cancelRequest = outgoingRequest.createCancel();
					}
					
					//https://code.google.com/p/sipservlets/issues/detail?id=272 Adding reason headers if needed
					if(protocol != null && reasonCode != null && reasonText != null
							&& protocol.length == reasonCode.length && reasonCode.length == reasonText.length) {
						for (int i = 0; i < protocol.length; i++) {
							String reasonHeaderValue = protocol[i] + ";cause=" + reasonCode[i];
							if(reasonText[i] != null && reasonText[i].trim().length() > 0) {
								reasonHeaderValue = reasonHeaderValue.concat(";text=\"" + reasonText[i] + "\"");
							}
							((SipServletRequestImpl)cancelRequest).setHeaderInternal("Reason", reasonHeaderValue, false);
							
						}
					}
					cancelRequest.send();
				} else {
					// We dont send cancel, but we must stop the invite retrans
					SIPClientTransaction tx = (SIPClientTransaction) outgoingRequest.getTransaction();
					
					if(tx != null) {
						StaticServiceHolder.disableRetransmissionTimer.invoke(tx);
						//disableTimeoutTimer.invoke(tx);
					} else {
						logger.warn("Transaction is null. Can not stop retransmission, they are already dead in the branch.");
					}
					/*
					try {
						//tx.terminate();
						// Do not terminate the tx here, because com.bea.sipservlet.tck.agents.spec.ProxyTest.testProxyCancel test is failing. If the tx
						// is terminated 100 Trying is dropped at JSIP.
					} catch(Exception e2) {
						logger.error("Can not terminate transaction", e2);
					}*/

				}
				canceled = true;
			}
			if(!this.isStarted() &&
					(outgoingRequest.getMethod().equalsIgnoreCase(Request.INVITE) ||
							// https://code.google.com/p/sipservlets/issues/detail?id=253
							outgoingRequest.getMethod().equalsIgnoreCase(Request.PRACK))) {
				canceled = true;	
			}
		}
		catch(Exception e) {
			throw new IllegalStateException("Failed canceling proxy branch", e);
		} finally {
			onBranchTerminated();
		}
			
	}
	
	// This will be called when we are sure this branch will not succeed and we moved on to other branches.
	public void onBranchTerminated() {
		if(outgoingRequest != null) {
			String txid = ((ViaHeader) outgoingRequest.getMessage().getHeader(ViaHeader.NAME)).getBranch();
			proxy.removeTransaction(txid);
		}
	}
	
	/* (non-Javadoc)
	 * @see javax.servlet.sip.ProxyBranch#getProxy()
	 */
	public Proxy getProxy() {
		return proxy;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.ProxyBranch#getProxyBranchTimeout()
	 */
	public int getProxyBranchTimeout() {
		return proxyBranchTimeout;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.ProxyBranch#getRecordRouteURI()
	 */
	public SipURI getRecordRouteURI() {
		if(this.getRecordRoute()) {
			if(this.recordRouteURI == null && this.recordRouteURIString == null) 
				this.recordRouteURIString = DEFAULT_RECORD_ROUTE_URI;
			
			if(recordRouteURIString != null) {
				try {
					recordRouteURI = ((SipURI)proxy.getSipFactoryImpl().createURI(recordRouteURIString));
					recordRouteURIString = null;
				} catch (ServletParseException e) {
					logger.error("A problem occured while setting the target URI while proxying a request " + recordRouteURI, e);
					return null;
				}
			}
			return recordRouteURI;
		}
		
		else throw new IllegalStateException("Record Route not enabled for this ProxyBranch. You must call proxyBranch.setRecordRoute(true) before getting an URI.");
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.ProxyBranch#getRecursedProxyBranches()
	 */
	public List<ProxyBranch> getRecursedProxyBranches() {
		if(recursedBranches == null) {
			return new ArrayList<ProxyBranch>();
		}
		return recursedBranches;
	}
	
	public void addRecursedBranch(ProxyBranchImpl branch) {
		if(recursedBranches == null) {
			recursedBranches = new ArrayList<ProxyBranch>();
		}
		recursedBranches.add(branch);
	}

	/**
	 * from the given request in param, find the current corresponding matching forwarded orginal request
	 * For a given ACK by example, there might be an UPDATE in between which make the outgoing requet not the INVITE one and can mess up
	 * the branch id generation for the ACK (200 OK would have had the same branch id as UPDATE) 
	 */
	public SipServletRequestImpl getMatchingRequest(
			SipServletRequestImpl request) {
		if(request.getMethod().equals(Request.ACK)) {
			Iterator<TransactionApplicationData> ongoingTransactions = proxy.getTransactionMap().values().iterator();
			// Issue 1837 http://code.google.com/p/mobicents/issues/detail?id=1837
			// ACK was received by JAIN-SIP but was not routed to application
			// we need to go through the set of all ongoing tx since and INFO request can be received before a reINVITE tx has been completed
			if(ongoingTransactions != null) {
				if(logger.isDebugEnabled()) {
                    logger.debug("going through all tx to check if we have the matching request to the ACK for branchId");
                }
				while (ongoingTransactions.hasNext()) {
					TransactionApplicationData transactionApplicationData = ongoingTransactions.next();					
					final SipServletMessageImpl sipServletMessage = transactionApplicationData.getSipServletMessage();
					if(sipServletMessage != null && sipServletMessage instanceof SipServletRequestImpl && 
							((MessageExt)request.getMessage()).getCSeqHeader().getSeqNumber() == ((MessageExt)sipServletMessage.getMessage()).getCSeqHeader().getSeqNumber() && 
							((MessageExt)request.getMessage()).getCallIdHeader().getCallId().equals(((MessageExt)sipServletMessage.getMessage()).getCallIdHeader().getCallId())) {
						return (SipServletRequestImpl)sipServletMessage;
					}
				}
			}
			return null;
		} else {
			return outgoingRequest;
		}		
	}
	
	/* (non-Javadoc)
	 * @see javax.servlet.sip.ProxyBranch#getRequest()
	 */
	public SipServletRequest getRequest() {
		return outgoingRequest;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.ProxyBranch#getResponse()
	 */
	public MobicentsSipServletResponse getResponse() {
		return lastResponse;
	}
	
	public void setResponse(MobicentsSipServletResponse response) {
		lastResponse = (SipServletResponseImpl) response;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.ProxyBranch#isStarted()
	 */
	public boolean isStarted() {
		return started;
	}

	/* (non-Javadoc)
	 * @see javax.servlet.sip.ProxyBranch#setProxyBranchTimeout(int)
	 */
	public void setProxyBranchTimeout(int seconds) {
		if(logger.isDebugEnabled()) {
			logger.debug("setProxyBranchTimeout");
		}
		if(seconds<=0) 
			throw new IllegalArgumentException("Negative or zero timeout not allowed");
		
		if(isCanceled() || isTimedOut()) {
			logger.error("Cancelled or timed out proxy branch should not be updated with new timeout values");
			return;
		}
		
		this.proxyBranchTimeout = seconds;
		if(this.started) updateTimer(false, originalRequest.getSipApplicationSession(false));
	}
	
	/**
	 * After the branch is initialized, this method proxies the initial request to the
	 * specified destination. Subsequent requests are proxied through proxySubsequentRequest
	 */
	public void start()	{
		if(logger.isDebugEnabled()) {
			logger.debug("start");
		}
		
		if(started) {
			throw new IllegalStateException("Proxy branch alredy started!");
		}
		if(canceled) {
			throw new IllegalStateException("Proxy branch was cancelled, you must create a new branch!");
		}
		if(timedOut) {
			throw new IllegalStateException("Proxy brnach has timed out!");
		}
		if(proxy.getAckReceived()) {
			throw new IllegalStateException("An ACK request has been received on this proxy. Can not start new branches.");
		}
		
		// Initialize these here for efficiency.
		if(logger.isDebugEnabled()) {
			logger.debug("start - calling updateTimer");
		}
		updateTimer(false, originalRequest.getSipApplicationSession(false));		
		
		SipURI recordRoute = null;
		
		// If the proxy is not adding record-route header, set it to null and it
		// will be ignored in the Proxying
		if(proxy.getRecordRoute() || this.getRecordRoute()) {
			if(recordRouteURI == null && recordRouteURIString == null) {
				recordRouteURIString = DEFAULT_RECORD_ROUTE_URI;
			}
			if(recordRouteURIString != null) {
				try {
					recordRouteURI = ((SipURI)proxy.getSipFactoryImpl().createURI(recordRouteURIString));
				} catch (ServletParseException e) {
					logger.error("A problem occured while setting the target URI while proxying a request " + recordRouteURIString, e);
				}
			}
			recordRoute = recordRouteURI;
		}
		addTransaction(originalRequest);

		URI destination = null;
                //app may have modified the branch request, so give it priority
                //fixes https://github.com/RestComm/sip-servlets/issues/131
                if (outgoingRequest.getRequestURI().equals(this.getProxy().getOriginalRequest().getRequestURI()))
                {
                    if(targetURI != null) {
                            try {
                                    destination = proxy.getSipFactoryImpl().createURI(targetURI);
                            } catch (ServletParseException e) {
                                    logger.error("A problem occured while setting the target URI while proxying a request " + targetURI, e);
                            }
                    }                    
                } else {
                    //the app has mofified the requestURI after branch creation..
                     destination = outgoingRequest.getRequestURI();
                }
		Request cloned = ProxyUtils.createProxiedRequest(
				outgoingRequest,
				this,
				destination,
				this.outboundInterface,
				recordRoute, 
				this.pathURI);
		//tells the application dispatcher to stop routing the original request
		//since it has been proxied
		originalRequest.setRoutingState(RoutingState.PROXIED);
		
		if(logger.isDebugEnabled()) {
			logger.debug("Proxy Branch 1xx Timeout set to " + proxyBranch1xxTimeout);
		}
		if(proxyBranch1xxTimeout > 0) {
			proxy1xxTimeoutTask = new ProxyBranchTimerTask(this, ResponseType.INFORMATIONAL, originalRequest.getSipApplicationSession(false));				
			proxy.getProxyTimerService().schedule(proxy1xxTimeoutTask, proxyBranch1xxTimeout * 1000L);
			proxyBranch1xxTimerStarted = true;
		}
		
		started = true;
		forwardRequest(cloned, false);		
	}

    /**
	 * Forward the request to the specified destination. The method is used internally.
	 * @param request
	 * @param subsequent Set to false if the the method is initial
	 */
	private void forwardRequest(Request request, boolean subsequent) {

		if(logger.isDebugEnabled()) {
			logger.debug("creating cloned Request for proxybranch " + request);
		}
		final SipServletRequestImpl clonedRequest = (SipServletRequestImpl) proxy.getSipFactoryImpl().getMobicentsSipServletMessageFactory().createSipServletRequest(
				request,
				null,
				null, null, false);
		
		if(logger.isDebugEnabled()) {
			logger.debug("forwardRequest - clonedRequest=" + clonedRequest);
		}
		
		if(subsequent) {
			if(logger.isDebugEnabled()) {
				logger.debug("forwardRequest - setRoutingState to SUBSEQUENT");
			}
			clonedRequest.setRoutingState(RoutingState.SUBSEQUENT);
		}
		
		this.outgoingRequest = clonedRequest;
		
		// Initialize the sip session for the new request if initial
		final MobicentsSipSession originalSipSession = originalRequest.getSipSession();
		if(logger.isDebugEnabled()) {
			logger.debug("forwardRequest - originalSipSession=" + originalSipSession);
			logger.debug("forwardRequest - originalRequest.getCurrentApplicationName()=" + originalRequest.getCurrentApplicationName());
		}
		
		clonedRequest.setCurrentApplicationName(originalRequest.getCurrentApplicationName());
		if(clonedRequest.getCurrentApplicationName() == null && subsequent) {
			if(logger.isDebugEnabled()) {
				logger.debug("forwardRequest - originalSipSession.getSipApplicationSession().getApplicationName()=" + originalSipSession.getSipApplicationSession().getApplicationName());
			}
			clonedRequest.setCurrentApplicationName(originalSipSession.getSipApplicationSession().getApplicationName());
		}
		clonedRequest.setSipSession(originalSipSession);
		final MobicentsSipSession newSession = (MobicentsSipSession) clonedRequest.getSipSession();
		try {
			if(logger.isDebugEnabled()) {
				logger.debug("forwardRequest - calling setHandler - originalSipSession.getHandler()=" + originalSipSession.getHandler());
			}
			newSession.setHandler(originalSipSession.getHandler());
		} catch (ServletException e) {
			logger.error("could not set the session handler while forwarding the request", e);
			throw new RuntimeException("could not set the session handler while forwarding the request", e);
		}
		
		// Use the original dialog in the new session
		// commented out proxy applications shouldn't use any dialogs !!!
//		newSession.setSessionCreatingDialog(originalSipSession.getSessionCreatingDialog());
		
		// And set a reference to the proxy		
		newSession.setProxy(proxy);		
				
		try {
			if(logger.isDebugEnabled()) {
				logger.debug("forwardRequest - calling checkRequest");
			}
			RFC5626Helper.checkRequest(this, request, originalRequest);
		} catch (IncorrectFlowIdentifierException e1) {
			if(logger.isDebugEnabled()) {
				logger.debug("forwardRequest - checkRequest threw an exception", e1);
			}
			logger.warn(e1.getMessage());
			this.cancel();
			try {
				originalRequest.createResponse(403).send();
			} catch (IOException e) {
				logger.error("couldn't send 403 response", e1);
			}
			return;
		}
		//JSR 289 Section 15.1.6
		if(!subsequent) {
			if(logger.isDebugEnabled()) {
				logger.debug("forwardRequest - not subsequent");
			}
			// Subsequent requests can't have a routing directive?
			clonedRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, originalRequest);
		}
		if(logger.isDebugEnabled()) {
			logger.debug("forwardRequest - setting the proxy branch");
		}
		clonedRequest.getTransactionApplicationData().setProxyBranch(this);			
		try {
			if(logger.isDebugEnabled()) {
				logger.debug("forwardRequest - sending the cloned request=" + clonedRequest);
			}
			clonedRequest.send();			
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		proxy.putTransaction(clonedRequest);
	}	

	/**
	 * A callback. Here we receive all responses from the proxied requests we have sent.
	 * 
	 * @param response
	 * @throws DispatcherException 
	 */
	public void onResponse(final MobicentsSipServletResponse response, final int status) throws DispatcherException
	{
		// If we are canceled but still receiving provisional responses try to cancel them
		if(canceled && status < 200) {
			if(logger.isDebugEnabled()) {
				logger.debug("ProxyBranch " + this + " with outgoing request " + outgoingRequest + " cancelled and still receiving provisional response, trying to cancel them");
			}
			try {
				final SipServletRequest cancelRequest = outgoingRequest.createCancel();
				cancelRequest.send();
			} catch (Exception e) {
				if(logger.isDebugEnabled()) {
					logger.debug("Failed to cancel again a provisional response " + response.toString()
							, e);
				}
			}
		}

		// We have already sent TRYING, don't send another one
		if(status == 100) {
			if(logger.isDebugEnabled() && proxyBranch1xxTimerStarted) {
				logger.debug("1xx received, cancelling 1xx timer ");
			}
			cancel1xxTimer();
			return;
		}
		
		// Send informational responses back immediately
		if((status > 100 && status < 200) || (status == 200 &&
				(Request.PRACK.equals(response.getMethod()) || Request.UPDATE.equals(response.getMethod()))))
		{
			// Deterimine if the response is reliable. We just look at RSeq, because
			// every such response is required to have it.
			if(response.getHeader("RSeq") != null) {
				this.setWaitingForPrack(true); // this branch is expecting a PRACK now
			}
			
			final SipServletResponseImpl proxiedResponse = 
				ProxyUtils.createProxiedResponse(response, this);
			
			if(proxiedResponse == null) {
				if(logger.isDebugEnabled())
					logger.debug("Response dropped because it was addressed to this machine.");
				return; // this response was addressed to this proxy
			}
			
			try {
				String branch = ((Via)proxiedResponse.getMessage().getHeader(Via.NAME)).getBranch();
				synchronized(this.ongoingTransactions) {
					for(TransactionRequest tr : this.ongoingTransactions) {
						if(tr.branchId.equals(branch)) {
							((SipServletResponseImpl)proxiedResponse).setTransaction(tr.request.getTransaction());
							((SipServletResponseImpl)proxiedResponse).setOriginalRequest(tr.request);
							break;
						}
					}
				}

				proxiedResponse.send();
				if(logger.isDebugEnabled())
					logger.debug("Proxy response sent out sucessfully");
			} catch (Exception e) {
				logger.error("A problem occured while proxying a response", e);
			}
			if(logger.isDebugEnabled())
				logger.debug("SipSession state " + response.getSipSession().getState());
			if(status == 200 &&
				(Request.PRACK.equals(response.getMethod()) || Request.UPDATE.equals(response.getMethod()))
				// Added for http://code.google.com/p/sipservlets/issues/detail?id=41
				&& State.EARLY.equals(response.getSipSession().getState())) {
				
				updateTimer(true, response.getSipApplicationSession(false));
			}
			
			if(logger.isDebugEnabled())
					logger.debug("About to return from onResponse");
			
			return;
		}
		
		// Non-provisional responses must also cancel the timer, otherwise it will timeout
		// and return multiple responses for a single transaction.
		cancelTimer();
		
		if(status >= 600) // Cancel all 10.2.4
			this.proxy.cancelAllExcept(this, null, null, null, false);
		
		// FYI: ACK is sent automatically by jsip when needed
		
		boolean recursed = false;
		if(status >= 300 && status < 400 && recurse) {
			String contact = response.getHeader("Contact");
			if(contact != null) {
				//javax.sip.address.SipURI uri = SipFactoryImpl.addressFactory.createAddress(contact);
				try {
					if(logger.isDebugEnabled())
						logger.debug("Processing recursed response");
					int start = contact.indexOf('<');
					int end = contact.indexOf('>');
					contact = contact.substring(start + 1, end);
					URI uri = proxy.getSipFactoryImpl().createURI(contact);
					ArrayList<SipURI> list = new ArrayList<SipURI>();
					list.add((SipURI)uri);
					List<ProxyBranch> pblist = proxy.createProxyBranches(list);
					ProxyBranchImpl pbi = (ProxyBranchImpl)pblist.get(0);
					this.addRecursedBranch(pbi);
					pbi.start();
					recursed = true;
				} catch (ServletParseException e) {
					throw new IllegalArgumentException("Can not parse contact header", e);
				}
			}
		}		
		if(status >= 200 && !recursed)
		{
			
			if(proxy.getFinalBranchForSubsequentRequests() == null ||
					(outgoingRequest != null && outgoingRequest.isInitial())) {
				if(logger.isDebugEnabled())
					logger.debug("Handling final response for initial request");
				this.proxy.onFinalResponse(this);
			} else {
				if(logger.isDebugEnabled())
					logger.debug("Handling final response for non-initial request");				
				this.proxy.sendFinalResponse(response, this);
			}
		}
		
	}

	/**
	 * Has the branch timed out?
	 * 
	 * @return
	 */
	public boolean isTimedOut() {
		return timedOut;
	}
	
	// https://code.google.com/p/sipservlets/issues/detail?id=238
	public void addTransaction(SipServletRequestImpl request) {
        if(!request.getMethod().equalsIgnoreCase("ACK") && !request.getMethod().equalsIgnoreCase("PRACK")) {
                String branch = ((Via)request.getMessage().getHeader(Via.NAME)).getBranch();
                if(this.ongoingTransactions.add(new TransactionRequest(branch, request))) {
                        request.getTransactionApplicationData().setProxyBranch(this);
                        if(logger.isDebugEnabled()) {
                                logger.debug("Added transaction "+branch+" to proxy branch.");
                        }
                }                                               
        }                               
    }
	
	// https://code.google.com/p/sipservlets/issues/detail?id=238
	public void removeTransaction(String branch) {
		synchronized(this.ongoingTransactions) {
			TransactionRequest remove = null;
			for(TransactionRequest tr : this.ongoingTransactions) {
				if(tr.branchId.equals(branch)) {
					remove = tr;
					break;
				}
			}
			if(remove != null) {
				boolean removed = this.ongoingTransactions.remove(remove);
				if(logger.isDebugEnabled()) {
					logger.debug("Removed transaction " + branch + " from proxy branch ? " + removed);
				}
			} else {
				if(logger.isDebugEnabled()) {
					logger.debug("Removing transaction " + branch + " from proxy branch FAILED. Not found.");
				}
			}
		}
	}
	
	/**
	 * Call this method when a subsequent request must be proxied through the branch.
	 * 
	 * @param request
	 */
	public void proxySubsequentRequest(MobicentsSipServletRequest sipServletRequest) {
		SipServletRequestImpl request = (SipServletRequestImpl) sipServletRequest;
		
		MobicentsSipServletResponse lastFinalResponse = (MobicentsSipServletResponse) request.getLastFinalResponse();
		if(lastFinalResponse != null && lastFinalResponse.isMessageSent()) {
		    // https://code.google.com/p/sipservlets/issues/detail?id=21
		    if(logger.isDebugEnabled()) {
		        logger.debug("Not proxying request as final response has already been sent for " + request);
		    }
		    return;
	    }
		
		addTransaction(request);
		// A re-INVITE needs special handling without going through the dialog-stateful methods
		if(request.getMethod().equalsIgnoreCase("INVITE")) {
			if(logger.isDebugEnabled()) {
				logger.debug("Proxying reinvite request " + request);
			}			
			proxyDialogStateless(request);
			return;
		}
		
		if(logger.isDebugEnabled()) {
			logger.debug("Proxying subsequent request " + request);
		}
		
		// Update the last proxied request
		request.setRoutingState(RoutingState.PROXIED);
		if(!request.getMethod().equalsIgnoreCase(Request.ACK) ) {
			proxy.setOriginalRequest(request);
			this.originalRequest = request;
		}
		
		// No proxy params, sine the target is already in the Route headers
//		final ProxyParams params = new ProxyParams(null, null, null, null);
		Request clonedRequest = null;
		if(request.getMethod().equalsIgnoreCase(Request.NOTIFY) || request.getMethod().equalsIgnoreCase(Request.SUBSCRIBE)) {
			// https://github.com/RestComm/sip-servlets/issues/121 http://tools.ietf.org/html/rfc6665#section-4.3
			clonedRequest = ProxyUtils.createProxiedRequest(request, this, null, outboundInterface, recordRouteURI, null);
		} else {
			clonedRequest = ProxyUtils.createProxiedRequest(request, this, null, null, null, null);
		}

//      There is no need for that, it makes application composition fail (The subsequent request is not dispatched to the next application since the route header is removed)
//		RouteHeader routeHeader = (RouteHeader) clonedRequest.getHeader(RouteHeader.NAME);
//		if(routeHeader != null) {
//			if(!((SipApplicationDispatcherImpl)proxy.getSipFactoryImpl().getSipApplicationDispatcher()).isRouteExternal(routeHeader)) {
//				clonedRequest.removeFirst(RouteHeader.NAME);	
//			}
//		}

		// https://telestax.atlassian.net/browse/MSS-153 perf optimization : we update the timer only on non ACK
		if(!clonedRequest.getMethod().equalsIgnoreCase(Request.ACK) ) {
			if(logger.isDebugEnabled()) {
				logger.debug("proxySubsequentRequest - calling updateTimer - request " + request);
			}
			updateTimer(false, request.getSipApplicationSession(false)); 
		}
 
		try {
			// Reset the proxy supervised state to default Chapter 6.2.1 - page down list bullet number 6
			proxy.setSupervised(true);
			if(clonedRequest.getMethod().equalsIgnoreCase(Request.ACK) ) { //|| clonedRequest.getMethod().equalsIgnoreCase(Request.PRACK)) {
				if(logger.isDebugEnabled()) {
					logger.debug("proxySubsequentRequest - clonedRequest is ACK - request " + request);
				}
				
				// we mark them as accessed so that HA replication can occur
				final MobicentsSipSession sipSession = request.getSipSession();
				final MobicentsSipApplicationSession sipApplicationSession = sipSession.getSipApplicationSession();
				sipSession.access();
				if(sipApplicationSession != null) {
					sipApplicationSession.access();
				}
				final String transport = JainSipUtils.findTransport(clonedRequest);
				SipFactoryImpl sipFactoryImpl = proxy.getSipFactoryImpl();
				SipNetworkInterfaceManager sipNetworkInterfaceManager = sipFactoryImpl.getSipNetworkInterfaceManager();
				final SipProvider sipProvider = sipNetworkInterfaceManager.findMatchingListeningPoint(
						transport, false).getSipProvider();
				SipConnector sipConnector = StaticServiceHolder.sipStandardService.findSipConnector(transport);
				
				// Optimizing the routing for AR (if any)
				if(sipConnector.isUseStaticAddress()) {
					JainSipUtils.optimizeRouteHeaderAddressForInternalRoutingrequest(
							sipConnector, clonedRequest, sipSession, sipFactoryImpl, transport);
					try {
						JainSipUtils.optimizeViaHeaderAddressForStaticAddress(sipConnector, clonedRequest, sipFactoryImpl, transport);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
				try {
					RFC5626Helper.checkRequest(this, clonedRequest, originalRequest);
				} catch (IncorrectFlowIdentifierException e1) {
					logger.warn(e1.getMessage());		
					this.cancel();
					return;
				}
				sipProvider.sendRequest(clonedRequest);
				sipFactoryImpl.getSipApplicationDispatcher().updateRequestsStatistics(clonedRequest, false);
			}
			else {				
				if(logger.isDebugEnabled()) {
					logger.debug("proxySubsequentRequest - calling forwardRequest - request " + request);
				}
				forwardRequest(clonedRequest, true);
			}
			
		} catch (SipException e) {
			logger.error("A problem occured while proxying a subsequent request", e);
		}
	}
	
	/**
	 * This method proxies requests without updating JSIP dialog state. PRACK and re-INVITE
	 * requests require this kind of handling because:
	 * 1. PRACK occurs before a dialog has been established (and also produces OKs before
	 *  the final response)
	 * 2. re-INVITE when sent with the dialog method resets the internal JSIP CSeq counter
	 *  to 1 every time you need it, which causes issues like 
	 *  http://groups.google.com/group/mobicents-public/browse_thread/thread/1a22ccdc4c481f47
	 * 
	 * @param request
	 */
	public void proxyDialogStateless(SipServletRequestImpl request) {
		if(logger.isDebugEnabled()) {
			logger.debug("Proxying request dialog-statelessly " + request);
		}
		final SipFactoryImpl sipFactoryImpl = proxy.getSipFactoryImpl();
		final SipApplicationDispatcher sipApplicationDispatcher = sipFactoryImpl.getSipApplicationDispatcher();
		final MobicentsSipSession sipSession = request.getSipSession();
		final MobicentsSipApplicationSession sipAppSession = sipSession.getSipApplicationSession();
		// Update the last proxied request
		request.setRoutingState(RoutingState.PROXIED);
		this.prackOriginalRequest = request;
				
		URI targetURI = null; 
		String targetURIString = null; 
		if(request.getMethod().equals(Request.PRACK) || request.getMethod().equals(Request.ACK)) {
			targetURIString = this.targetURI;
		}
		
		// Determine the direction of the request. Either it's from the dialog initiator (the caller)
		// or from the callee
		if(!((MessageExt)request.getMessage()).getFromHeader().getTag().toString().equals(proxy.getCallerFromTag())) {
			// If it's from the callee we should send it in the other direction
			targetURIString = proxy.getPreviousNode();
		}
		if(targetURIString != null) {
			try {
				targetURI = sipFactoryImpl.createURI(targetURIString);
			} catch (ServletParseException e) {
				logger.error("A problem occured while setting the target URI while proxying a request " + targetURIString, e);
			}
		}
		SipURI recordRoute = null;
		if(recordRouteURI != null) {
			recordRoute = recordRouteURI;
		} else if (recordRouteURIString != null){
			try {
				recordRoute = ((SipURI)proxy.getSipFactoryImpl().createURI(recordRouteURIString));
			} catch (ServletParseException e) {
				logger.error("A problem occured while setting the target URI while proxying a request " + recordRouteURI, e);
			}
		}
		// https://code.google.com/p/sipservlets/issues/detail?id=274
		// as described in https://lists.cs.columbia.edu/pipermail/sip-implementors/2003-June/004986.html
		// we should record route on reINVITE as well for robustness in case of UA crash, so adding recordRouteURI in the call to this method
		Request clonedRequest = 
			ProxyUtils.createProxiedRequest(request, this, targetURI, null, recordRoute, null);

		ViaHeader viaHeader = (ViaHeader) clonedRequest.getHeader(ViaHeader.NAME);
		try {
			final String branch = JainSipUtils.createBranch(
					sipSession.getKey().getApplicationSessionId(),  
					sipApplicationDispatcher.getHashFromApplicationName(sipSession.getKey().getApplicationName()));			
			viaHeader.setBranch(branch);
		} catch (ParseException pe) {
			logger.error("A problem occured while setting the via branch while proxying a request", pe);
		}
	
		String transport = JainSipUtils.findTransport(clonedRequest);
		SipProvider sipProvider =sipFactoryImpl.getSipNetworkInterfaceManager().findMatchingListeningPoint(
				transport, false).getSipProvider();
		
		try {
			RFC5626Helper.checkRequest(this, clonedRequest, request);
		} catch (IncorrectFlowIdentifierException e1) {
			logger.warn(e1.getMessage());
			this.cancel();
			try {
				originalRequest.createResponse(403).send();
			} catch (IOException e) {
				logger.error("couldn't send 403 response", e1);
			}
			return;
		}
		
		if(logger.isDebugEnabled()) {
			logger.debug("Getting new Client Tx for request " + clonedRequest + "\n transport = " + transport);
		}
		// we mark them as accessed so that HA replication can occur		
		sipSession.access();
		if(sipAppSession != null) {
			sipAppSession.access();
		}

	    SipConnector sipConnector = StaticServiceHolder.sipStandardService.findSipConnector(transport);

		ClientTransaction ctx = null;	
		try {	
			if(sipConnector != null && sipConnector.isUseStaticAddress()) {
				javax.sip.address.URI uri = clonedRequest.getRequestURI();
				RouteHeader route = (RouteHeader) clonedRequest.getHeader(RouteHeader.NAME);
				if(route != null) {
					uri = route.getAddress().getURI();
				}
				if(uri.isSipURI()) {
					javax.sip.address.SipURI sipUri = (javax.sip.address.SipURI) uri;
					String host = sipUri.getHost();
					int port = sipUri.getPort();
					if(sipFactoryImpl.getSipApplicationDispatcher().isExternal(host, port, transport)) {
						viaHeader.setHost(sipConnector.getStaticServerAddress());
						viaHeader.setPort(sipConnector.getStaticServerPort());
					}
				}
				JainSipUtils.optimizeRouteHeaderAddressForInternalRoutingrequest(sipConnector,
						clonedRequest, sipSession, sipFactoryImpl, transport);
			}
			ctx = sipProvider.getNewClientTransaction(clonedRequest);
			JainSipUtils.setTransactionTimers((TransactionExt) ctx, sipApplicationDispatcher);
			
			TransactionApplicationData appData = (TransactionApplicationData) request.getTransactionApplicationData();
			appData.setProxyBranch(this);
			ctx.setApplicationData(appData);
			
			final SipServletRequestImpl clonedSipServletRequest = (SipServletRequestImpl) proxy.getSipFactoryImpl().getMobicentsSipServletMessageFactory().createSipServletRequest(
					clonedRequest,
					sipSession,
					ctx, null, false);
			appData.setSipServletMessage(clonedSipServletRequest);
					 			
			clonedSipServletRequest.setRoutingState(RoutingState.SUBSEQUENT);
			// make sure to store the outgoing request to make sure the branchid for a ACK to a future reINVITE if this one is INFO
			// by example will have the correct branchid and not the one from the INFO
			this.outgoingRequest = clonedSipServletRequest;
			
			ctx.sendRequest();
			sipFactoryImpl.getSipApplicationDispatcher().updateRequestsStatistics(clonedRequest, false);
		} catch (Exception e) {
			logger.error("A problem occured while proxying a request " + request + " in a dialog-stateless transaction", e);
			JainSipUtils.terminateTransaction(ctx);
		} 
	}
	
	/**
	 * This callback is called when the remote side has been idle too long while
	 * establishing the dialog.
	 * @throws DispatcherException 
	 *
	 */
	public void onTimeout(ResponseType responseType) throws DispatcherException
	{
		if(!proxy.getAckReceived()) {
			this.cancel();
			if(responseType == ResponseType.FINAL) {
				cancel1xxTimer();
			}
			this.timedOut = true;
			if(originalRequest != null) {
				List<ProxyBranchListener> proxyBranchListeners = originalRequest.getSipSession().getSipApplicationSession().getSipContext().getListeners().getProxyBranchListeners();
				if(proxyBranchListeners != null) {
					for (ProxyBranchListener proxyBranchListener : proxyBranchListeners) {
						proxyBranchListener.onProxyBranchResponseTimeout(responseType, this);
					}
				}
			}
			// Just do a timeout response
			proxy.onBranchTimeOut(this);
			logger.warn("Proxy branch has timed out");
		} else {
			logger.debug("ACKed proxybranch has timeout");
		}
	}
	
	/**
	 * Restart the timer. Call this method when some activity shows the remote
	 * party is still online.
	 * @param mobicentsSipApplicationSession 
	 *
	 */
	public void updateTimer(boolean cancel1xxTimer, MobicentsSipApplicationSession mobicentsSipApplicationSession) {
		if(logger.isDebugEnabled()) {
			logger.debug("updateTimer - cancel1xxTimer=" + cancel1xxTimer + ", mobicentsSipApplicationSession=" + mobicentsSipApplicationSession);
			if (mobicentsSipApplicationSession != null){
				logger.debug("updateTimer - cancel1xxTimer=" + cancel1xxTimer + ", mobicentsSipApplicationSession.getApplicationName()=" + mobicentsSipApplicationSession.getApplicationName());
				logger.debug("updateTimer - cancel1xxTimer=" + cancel1xxTimer + ", mobicentsSipApplicationSession.getSipContext()=" + mobicentsSipApplicationSession.getSipContext());
			}
		}
		if(cancel1xxTimer) {
			cancel1xxTimer();
		}
		cancelTimer();		
		if(proxyBranchTimeout > 0) {			
			synchronized (cTimerLock) {
				if(!proxyBranchTimerStarted) {
					try {
						final ProxyBranchTimerTask timerCTask = new ProxyBranchTimerTask(this, ResponseType.FINAL, mobicentsSipApplicationSession);
						if(logger.isDebugEnabled()) {
							logger.debug("Proxy Branch Timeout set to " + proxyBranchTimeout);
							logger.debug("updateTimer - proxy=" + proxy);
							if (proxy != null){
								logger.debug("updateTimer - proxy.getProxyTimerService()=" + proxy.getProxyTimerService());
							}
						}
						proxy.getProxyTimerService().schedule(timerCTask, proxyBranchTimeout * 1000L);
						proxyTimeoutTask = timerCTask;
						proxyBranchTimerStarted = true;
					} catch (IllegalStateException e) {
						logger.error("Unexpected exception while scheduling Timer C" ,e);
					}	
				}
			}
		}		
	}
	
	/**
	 * Stop the C Timer.
	 */
	public void cancelTimer() {
		synchronized (cTimerLock) {
			if (proxyTimeoutTask != null && proxyBranchTimerStarted) {
				proxyTimeoutTask.cancel();
				proxyTimeoutTask = null;
				proxyBranchTimerStarted = false;
			}
		}
	}

	/**
	 * Stop the Extension Timer for 1xx.
	 */
	public void cancel1xxTimer() {
		if (proxy1xxTimeoutTask != null && proxyBranch1xxTimerStarted) {
			proxy1xxTimeoutTask.cancel();
			proxy1xxTimeoutTask = null;
			proxyBranch1xxTimerStarted = false;
		}
	}

	public boolean isCanceled() {
		return canceled;
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean getAddToPath() {
		return isAddToPath;
	}

	/**
	 * {@inheritDoc}
	 */
	public SipURI getPathURI() {
		if(!isAddToPath) {
			throw new IllegalStateException("addToPath is not enabled!");
		}
		return this.pathURI;
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean getRecordRoute() {
		return recordRoutingEnabled;
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean getRecurse() {
		return recurse;
	}

	/**
	 * {@inheritDoc}
	 */
	public void setAddToPath(boolean isAddToPath) {
		if(started) {
			throw new IllegalStateException("Cannot set a record route on an already started proxy");
		}
		if(this.pathURI == null) {
			this.pathURI = new SipURIImpl (JainSipUtils.createRecordRouteURI( proxy.getSipFactoryImpl().getSipNetworkInterfaceManager(), null), ModifiableRule.NotModifiable);
		}		
		this.isAddToPath = isAddToPath;
	}
	
	public boolean isAddToPath() {
		return this.isAddToPath;
	}

	/**
	 * {@inheritDoc}
	 */
	public void setOutboundInterface(InetAddress inetAddress) {
		if(inetAddress == null) {
			throw new NullPointerException("outbound Interface param shouldn't be null");
		}
		checkSessionValidity();
		String address = inetAddress.getHostAddress();
		
		List<SipURI> list = proxy.getSipFactoryImpl().getSipNetworkInterfaceManager().getOutboundInterfaces();
		SipURI networkInterface = null;
		for(SipURI networkInterfaceURI : list) {
			if(networkInterfaceURI.toString().contains(address)) {
				networkInterface = networkInterfaceURI;
				break;
			}
		}
		if(networkInterface == null) throw new IllegalArgumentException("Network interface for " +
				outboundInterface + " not found");	
		
		outboundInterface = proxy.getSipFactoryImpl().createSipURI(null, address);		
	}

	/**
	 * {@inheritDoc}
	 */
	public void setOutboundInterface(InetSocketAddress inetSocketAddress) {
		if(inetSocketAddress == null) {
			throw new NullPointerException("outbound Interface param shouldn't be null");
		}
		checkSessionValidity();
		String address = inetSocketAddress.getAddress().getHostAddress() + ":" + inetSocketAddress.getPort();
		
		List<SipURI> list = proxy.getSipFactoryImpl().getSipNetworkInterfaceManager().getOutboundInterfaces();
		SipURI networkInterface = null;
		for(SipURI networkInterfaceURI : list) {
			if(networkInterfaceURI.toString().contains(address)) {
				networkInterface = networkInterfaceURI;
				break;
			}
		}
		if(networkInterface == null) throw new IllegalArgumentException("Network interface for " +
				outboundInterface + " not found");	
		
		outboundInterface = proxy.getSipFactoryImpl().createSipURI(null, address);		
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.javax.servlet.sip.ProxyExt#setOutboundInterface(javax.servlet.sip.SipURI)
	 */
	public void setOutboundInterface(SipURI outboundInterface) {
		checkSessionValidity();
		if(outboundInterface == null) {
			throw new NullPointerException("outbound Interface param shouldn't be null");
		}
		List<SipURI> list = proxy.getSipFactoryImpl().getSipNetworkInterfaceManager().getOutboundInterfaces();
		SipURI networkInterface = null;
		for(SipURI networkInterfaceURI : list) {
			if(networkInterfaceURI.equals(outboundInterface)) {
				networkInterface = networkInterfaceURI;
				break;
			}
		}
		
		if(networkInterface == null) throw new IllegalArgumentException("Network interface for " +
				outboundInterface + " not found");		
		
		this.outboundInterface = networkInterface;
	}

	/**
	 * {@inheritDoc}
	 */
	public void setRecordRoute(boolean isRecordRoute) {
		if(started) {
			throw new IllegalStateException("Proxy branch alredy started!");
		}
		recordRoutingEnabled = isRecordRoute;
	}

	/**
	 * {@inheritDoc}
	 */
	public void setRecurse(boolean isRecurse) {
		recurse = isRecurse;
	}	
	
	private void checkSessionValidity() {
		if(this.originalRequest.getSipSession().isValidInternal() && this.originalRequest.getSipSession().getSipApplicationSession().isValidInternal())
			return;
		throw new IllegalStateException("Invalid session.");
	}

	/**
	 * @param prackOriginalRequest the prackOriginalRequest to set
	 */
	public void setPrackOriginalRequest(SipServletRequestImpl prackOriginalRequest) {
		this.prackOriginalRequest = prackOriginalRequest;
	}

	/**
	 * @return the prackOriginalRequest
	 */
	public SipServletRequestImpl getPrackOriginalRequest() {
		return prackOriginalRequest;
	}

	public boolean isWaitingForPrack() {
		return waitingForPrack;
	}

	public void setWaitingForPrack(boolean waitingForPrack) {
		this.waitingForPrack = waitingForPrack;
	}

	/**
	 * @param proxy the proxy to set
	 */
	public void setProxy(ProxyImpl proxy) {
		if(logger.isDebugEnabled()) {
			logger.debug("setProxy - proxy=" + proxy);
		}
		this.proxy = proxy;
	}

	public void readExternal(ObjectInput in) throws IOException,
			ClassNotFoundException {
		cTimerLock = new Object();
		recurse = in.readBoolean();
		recordRoutingEnabled = in.readBoolean();
		started = in.readBoolean();
		timedOut = in.readBoolean();
		proxyBranchTimeout = in.readInt();
		proxyBranch1xxTimeout = in.readInt();
		canceled = in.readBoolean();
		isAddToPath = in.readBoolean();
		waitingForPrack = in.readBoolean();
		appSpecifiedRecordRoutingEnabled = in.readBoolean();
	}

	public void writeExternal(ObjectOutput out) throws IOException {
		out.writeBoolean(recurse);
		out.writeBoolean(recordRoutingEnabled);
		out.writeBoolean(started);
		out.writeBoolean(timedOut);
		out.writeInt(proxyBranchTimeout);
		out.writeInt(proxyBranch1xxTimeout);
		out.writeBoolean(canceled);
		out.writeBoolean(isAddToPath);
		out.writeBoolean(waitingForPrack);
		out.writeBoolean(appSpecifiedRecordRoutingEnabled);
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.javax.servlet.sip.ProxyBranchExt#getProxyBranch1xxTimeout()
	 */
	public int getProxyBranch1xxTimeout() {
		return proxyBranch1xxTimeout;
	}

	/*
	 * (non-Javadoc)
	 * @see org.mobicents.javax.servlet.sip.ProxyBranchExt#setProxyBranch1xxTimeout(int)
	 */
	public void setProxyBranch1xxTimeout(int timeout) {
		proxyBranch1xxTimeout= timeout;
		
	}

	/**
	 * @param outgoingRequest the outgoingRequest to set
	 */
	public void setOutgoingRequest(SipServletRequestImpl outgoingRequest) {
		this.outgoingRequest = outgoingRequest;
	}

	/**
	 * @param originalRequest the originalRequest to set
	 */
	public void setOriginalRequest(SipServletRequestImpl originalRequest) {
		this.originalRequest = originalRequest;
		if(originalRequest == null && recordRouteURI != null) {
			recordRouteURIString = recordRouteURI.toString();
			recordRouteURI = null;
		}
	}

	/**
	 * @return the originalRequest
	 */
	public SipServletRequestImpl getOriginalRequest() {
		return originalRequest;
	}

	/**
	 * @param targetURI the targetURI to set
	 */
	public void setTargetURI(String targetURI) {
		this.targetURI = targetURI;
	}

	/**
	 * @return the targetURI
	 */
	public String getTargetURI() {
		return targetURI;
	}

	@Override
	public void setRecordRouteURI(SipURI uri) {
		this.recordRouteURI = uri;
		this.recordRouteURIString = null;
	}

	/**
	 * @return the userSpecifiedRecordRoutingEnabled
	 */
	public boolean isAppSpecifiedRecordRoutingEnabled() {
		return appSpecifiedRecordRoutingEnabled;
	}
}
