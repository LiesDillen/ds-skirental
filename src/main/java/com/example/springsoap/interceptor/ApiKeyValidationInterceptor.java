package com.example.springsoap.interceptor;

import org.springframework.ws.server.endpoint.interceptor.EndpointInterceptorAdapter;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.SoapHeader;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapHeaderElement;

import javax.xml.namespace.QName;
import java.util.Iterator;

public class ApiKeyValidationInterceptor extends EndpointInterceptorAdapter {

    private static final String VALID_API_KEY = "Y7Jv3Dq4M1fN8K2sL5xZ9R0wQ6uP4eB7";
    private static final String HEADER_NAMESPACE = "http://skirental.io/gt/webservice";
    private static final String HEADER_LOCAL_PART = "apiKey";

    @Override
    public boolean handleRequest(MessageContext messageContext, Object endpoint) throws Exception {
        SoapMessage soapMessage = (SoapMessage) messageContext.getRequest();
        SoapHeader soapHeader = soapMessage.getSoapHeader();

        String apiKey = extractApiKey(soapHeader);

        // Validate API key
        if (!isValidApiKey(apiKey)) {
            throw new RuntimeException("Invalid API Key");
        }

        return true; // Allow the request to proceed to the endpoint
    }

    private String extractApiKey(SoapHeader soapHeader) {
        if (soapHeader == null) {
            return null;
        }

        QName apiKeyQName = new QName(HEADER_NAMESPACE, HEADER_LOCAL_PART);
        Iterator<SoapHeaderElement> iterator = soapHeader.examineAllHeaderElements();

        while (iterator.hasNext()) {
            SoapHeaderElement element = iterator.next();
            if (apiKeyQName.equals(element.getName())) {
                return element.getText();
            }
        }

        return null;
    }

    private boolean isValidApiKey(String apiKey) {
        return VALID_API_KEY.equals(apiKey);
    }
}