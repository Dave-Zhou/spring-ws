/*
 * Copyright 2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ws.transport.jms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.jms.BytesMessage;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Session;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.SOAPConstants;
import javax.xml.soap.SOAPMessage;

import junit.framework.TestCase;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.WebServiceConnection;

public class JmsMessageSenderIntegrationTest extends TestCase {

    private JmsMessageSender messageSender;

    private JmsTemplate jmsTemplate;

    private MessageFactory messageFactory;

    private static final String URI = "jms:RequestQueue";

    private static final String SOAP_ACTION = "http://springframework.org/DoIt";

    protected void setUp() throws Exception {
        ConnectionFactory connectionFactory = new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
        jmsTemplate = new JmsTemplate(connectionFactory);
        jmsTemplate.setDefaultDestinationName("RequestQueue");
        messageSender = new JmsMessageSender(connectionFactory);
        messageFactory = MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL);
    }

    public void testSendAndReceiveQueueNoResponse() throws Exception {
        WebServiceConnection connection = null;
        try {
            connection = messageSender.createConnection(URI);
            SOAPMessage saajMessage = messageFactory.createMessage();
            SoapMessage soapRequest = new SaajSoapMessage(saajMessage);
            soapRequest.setSoapAction(SOAP_ACTION);
            connection.send(soapRequest);
            BytesMessage jmsRequest = (BytesMessage) jmsTemplate.receive();
            validateMessage(jmsRequest);
        }
        finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private void validateMessage(BytesMessage message) throws JMSException, IOException {
        assertEquals("Invalid SOAPAction", SOAP_ACTION,
                message.getStringProperty(JmsTransportConstants.PROPERTY_SOAP_ACTION));
        assertEquals("Invalid binding version", "1.0",
                message.getStringProperty(JmsTransportConstants.PROPERTY_BINDING_VERSION));
        assertEquals("Invalid service IRI", URI, message.getStringProperty(JmsTransportConstants.PROPERTY_REQUEST_IRI));
        assertFalse("Message is Fault", message.getBooleanProperty(JmsTransportConstants.PROPERTY_IS_FAULT));
        assertTrue("Invalid Content Type",
                message.getStringProperty(JmsTransportConstants.PROPERTY_CONTENT_TYPE).indexOf("text/xml") != -1);
        assertTrue("No Content Length", message.getIntProperty(JmsTransportConstants.PROPERTY_CONTENT_LENGTH) > 0);

        assertTrue("Message has no contents", getMessageContents(message).length() > 0);

    }

    public void testSendAndReceiveResponse() throws Exception {
        WebServiceConnection connection = null;
        try {
            connection = messageSender.createConnection(URI);
            SoapMessage soapRequest = new SaajSoapMessage(messageFactory.createMessage());
            soapRequest.setSoapAction(SOAP_ACTION);
            connection.send(soapRequest);

            BytesMessage request = (BytesMessage) jmsTemplate.receive();
            validateMessage(request);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            messageFactory.createMessage().writeTo(bos);
            final byte[] buf = bos.toByteArray();
            jmsTemplate.send(request.getJMSReplyTo(), new MessageCreator() {

                public Message createMessage(Session session) throws JMSException {
                    BytesMessage response = session.createBytesMessage();
                    response.setStringProperty(JmsTransportConstants.PROPERTY_BINDING_VERSION, "1.0");
                    response.setIntProperty(JmsTransportConstants.PROPERTY_CONTENT_LENGTH, buf.length);
                    response.setStringProperty(JmsTransportConstants.PROPERTY_CONTENT_TYPE, "text/xml");
                    response.setBooleanProperty(JmsTransportConstants.PROPERTY_IS_FAULT, false);
                    response.setStringProperty(JmsTransportConstants.PROPERTY_REQUEST_IRI, URI);
                    response.setStringProperty(JmsTransportConstants.PROPERTY_SOAP_ACTION, SOAP_ACTION);

                    response.writeBytes(buf);
                    return response;
                }
            });
            SoapMessage response = (SoapMessage) connection.receive(new SaajSoapMessageFactory(messageFactory));
            assertNotNull("No response received", response);
            assertEquals("Invalid SOAPAction", SOAP_ACTION, response.getSoapAction());
            assertFalse("Message is fault", response.hasFault());
        }
        finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private String getMessageContents(BytesMessage message) throws JMSException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int bytesRead = -1;
        while ((bytesRead = message.readBytes(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
        out.flush();
        return out.toString("UTF-8");
    }
}