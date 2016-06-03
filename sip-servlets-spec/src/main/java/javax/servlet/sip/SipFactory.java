/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
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

package javax.servlet.sip;
/**
 * Factory interface for a variety of SIP Servlet API abstractions.
 * SIP servlet containers are requried to make a SipFactory instance available to applications through a ServletContext attribute with name javax.servlet.sip.SipFactory.
 */
public interface SipFactory{
    /**
     * Returns a Address corresponding to the specified string. The resulting object can be used, for example, as the value of From or To headers of locally initiated SIP requests.
     * The special argument "*" results in a wildcard Address being returned, that is, an Address for which isWildcard returns true. Such addresses are for use in Contact headers only.
     * The specified address string must be UTF-8 encoded. Furthermore, if the URI component of the address string contains any reserved characters then they must be escaped according to RFC2396 as indicated for createURI(String)
     */
    javax.servlet.sip.Address createAddress(java.lang.String addr) throws javax.servlet.sip.ServletParseException;

    /**
     * Returns an Address with the specified URI and no display name.
     */
    javax.servlet.sip.Address createAddress(javax.servlet.sip.URI uri);

    /**
     * Returns a new Address with the specified URI and display name.
     */
    javax.servlet.sip.Address createAddress(javax.servlet.sip.URI uri, java.lang.String displayName);

    /**
     * Returns a new SipApplicationSession. This is useful, for example, when an application is being initialized and wishes to perform some signaling action.
     */
    javax.servlet.sip.SipApplicationSession createApplicationSession();

    /**
     * Returns a new SipApplicationSession identified by the specified SipApplicationKey. 
     * This is same as the one generated by the method annotated with @SipApplicationKey annotation. 
     * This allows a way to associate incoming requests to an already existing SipApplicationSession.
     * @param sipApplicationKey - id for the SipApplicationSession
     * @return a new SipApplicationSession object with the specified id
     * @since 1.1
     */
    SipApplicationSession createApplicationSessionByKey(java.lang.String sipApplicationKey);
    
    /**
     * Creates a new AuthInfo object that can be used to provide authentication information on servlet initiated requests.
     * @return AuthInfo a new instance of AuthInfo
     */
    AuthInfo createAuthInfo();
    
    /**
     * Creates a new Parameterable parsed from the specified string. 
     * The string must be in the following format: field-name: field-value *(;parameter-name=parameter-value)
     * where the field-value may be in name-addr or addr-spec format as defined in 
     * RFC 3261 or may be any sequence of tokens till the first semicolon.
     */
    javax.servlet.sip.Parameterable createParameterable(java.lang.String s, javax.servlet.sip.SipSession sipSession) throws ServletParseException;

    /**
     * Returns a new request object with the specified request method, From, and To headers. The returned request object exists in a new SipSession which belongs to the specified SipApplicationSession.
     * This method is used by servlets acting as SIP clients in order to send a request in a new call leg. The container is responsible for assigning the request appropriate Call-ID and CSeq headers, as well as Contact header if the method is not REGISTER.
     * This method makes a copy of the from and to arguments and associates them with the new SipSession. Any component of the from and to URIs not allowed in the context of SIP From and To headers are removed from the copies. This includes, headers and various parameters. Also, a "tag" parameter in either of the copied from or to is also removed, as it is illegal in an initial To header and the container will choose it's own tag for the From header. The copied from and to addresses can be obtained from the SipSession but must not be modified by applications.
     */
    javax.servlet.sip.SipServletRequest createRequest(javax.servlet.sip.SipApplicationSession appSession, java.lang.String method, javax.servlet.sip.Address from, javax.servlet.sip.Address to);

    /**
     * Returns a new request object with the specified request method, From, and To headers. The returned request object exists in a new SipSession which belongs to the specified SipApplicationSession.
     * This method is used by servlets acting as SIP clients in order to send a request in a new call leg. The container is responsible for assigning the request appropriate Call-ID and CSeq headers, as well as Contact header if the method is not REGISTER.
     * This method is functionally equivalent to: createRequest(method, f.createAddress(from), f.createAddress(to)); Note that this implies that if either of the from or to argument is a SIP URI containing parameters, the URI must be enclosed in angle brackets. Otherwise the address will be parsed as if the parameter belongs to the address and not the URI.
     */
    javax.servlet.sip.SipServletRequest createRequest(javax.servlet.sip.SipApplicationSession appSession, java.lang.String method, java.lang.String from, java.lang.String to) throws javax.servlet.sip.ServletParseException;

    /**
     * Returns a new request object with the specified request method, From, and To headers. The returned request object exists in a new SipSession which belongs to the specified SipApplicationSession.
     * This method is used by servlets acting as SIP clients in order to send a request in a new call leg. The container is responsible for assigning the request appropriate Call-ID and CSeq headers, as well as Contact header if the method is not REGISTER.
     * This method makes a copy of the from and to arguments and associates them with the new SipSession. Any component of the from and to URIs not allowed in the context of SIP From and To headers are removed from the copies. This includes, headers and various parameters. The from and to addresses can subsequently be obtained from the SipSession or the returned request object but must not be modified by applications.
     */
    javax.servlet.sip.SipServletRequest createRequest(javax.servlet.sip.SipApplicationSession appSession, java.lang.String method, javax.servlet.sip.URI from, javax.servlet.sip.URI to);

    /**
     * @deprecated usage of this method is deprecated. Setting the sameCallId flag to "true" actually breaks the provisions of [RFC 3261] where the Call-ID value is to be unique accross dialogs. Instead use a more general method defined on the B2buaHelper B2buaHelper.createRequest(SipServletRequest)
     * Creates a new request object belonging to a new SipSession. 
     * The new request is similar to the specified origRequest in that the method and the majority of header fields are copied from origRequest to the new request. 
     * The SipSession created for the new request also shares the same SipApplicationSession associated with the original request.
     * This method satisfies the following rules: 
     * The From header field of the new request has a new tag chosen by the container. 
     * The To header field of the new request has no tag. 
     * If the sameCallId argument is false, the new request (and the corresponding SipSession)
     * is assigned a new Call-ID. Record-Route and Via header fields are not copied. 
     * As usual, the container will add its own Via header field to the request 
     * when it's actually sent outside the application server. 
     * For non-REGISTER requests, the Contact header field is not copied but is populated by the container as usual.
     * This method provides a convenient and efficient way of constructing the second "leg" of a B2BUA application. 
     * It is used only for the initial request. 
     * Subsequent requests in either leg must be created using SipSession.createRequest(java.lang.String) as usual.
     */
    javax.servlet.sip.SipServletRequest createRequest(javax.servlet.sip.SipServletRequest origRequest, boolean sameCallId);

    /**
     * Constructs a SipURI with the specified user and host components. The scheme will initially be sip but the application may change it to sips by calling setSecure(true) on the returned SipURI. Likewise, the port number of the new URI is left unspecified but may subsequently be set by calling setPort on the returned SipURI.
     * If the specified URI string contains any reserved characters, then they must be escaped according to RFC2396.
     */
    javax.servlet.sip.SipURI createSipURI(java.lang.String user, java.lang.String host);

    /**
     * Returns a URI object corresponding to the specified string, which should represent an escaped SIP, SIPS, or tel URI. The URI may then be used as request URI in SIP requests or as the URI component of
     * objects.
     * Implementations must be able to represent URIs of any scheme. This method returns a SipURI object if the specified string is a sip or a sips URI, and a TelURL object if it's a tel URL.
     * If the specified URI string contains any reserved characters, then they must be escaped according to RFC2396.
     */
    javax.servlet.sip.URI createURI(java.lang.String uri) throws javax.servlet.sip.ServletParseException;

}
