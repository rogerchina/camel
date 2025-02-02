/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.aws2.sqs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.apache.camel.support.DefaultProducer;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.URISupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueResponse;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesResponse;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.PurgeQueueResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * A Producer which sends messages to the Amazon Web Service Simple Queue Service
 * <a href="http://aws.amazon.com/sqs/">AWS SQS</a>
 */
public class Sqs2Producer extends DefaultProducer {

    private static final Logger LOG = LoggerFactory.getLogger(Sqs2Producer.class);

    private static final int MAX_ATTRIBUTES = 10;

    private transient String sqsProducerToString;

    public Sqs2Producer(Sqs2Endpoint endpoint) {
        super(endpoint);
        if (endpoint.getConfiguration().isFifoQueue()
                && ObjectHelper.isEmpty(getEndpoint().getConfiguration().getMessageGroupIdStrategy())) {
            throw new IllegalArgumentException("messageGroupIdStrategy must be set for FIFO queues.");
        }
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Sqs2Operations operation = determineOperation(exchange);
        if (ObjectHelper.isEmpty(operation)) {
            processSingleMessage(exchange);
        } else {
            switch (operation) {
                case sendBatchMessage:
                    sendBatchMessage(getClient(), exchange);
                    break;
                case deleteMessage:
                    deleteMessage(getClient(), exchange);
                    break;
                case listQueues:
                    listQueues(getClient(), exchange);
                    break;
                case purgeQueue:
                    purgeQueue(getClient(), exchange);
                    break;
                case deleteQueue:
                    deleteQueue(getClient(), exchange);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported operation");
            }
        }
    }

    public void processSingleMessage(final Exchange exchange) {
        String body = exchange.getIn().getBody(String.class);
        SendMessageRequest.Builder request = SendMessageRequest.builder().queueUrl(getQueueUrl()).messageBody(body);
        request.messageAttributes(translateAttributes(exchange.getIn().getHeaders(), exchange));
        addDelay(request, exchange);
        configureFifoAttributes(request, exchange);

        LOG.trace("Sending request [{}] from exchange [{}]...", request, exchange);

        SendMessageResponse result = getClient().sendMessage(request.build());

        LOG.trace("Received result [{}]", result);

        Message message = getMessageForResponse(exchange);
        message.setHeader(Sqs2Constants.MESSAGE_ID, result.messageId());
        message.setHeader(Sqs2Constants.MD5_OF_BODY, result.md5OfMessageBody());
    }

    private void sendBatchMessage(SqsClient amazonSQS, Exchange exchange) {
        SendMessageBatchRequest.Builder request = SendMessageBatchRequest.builder().queueUrl(getQueueUrl());
        Collection<SendMessageBatchRequestEntry> entries = new ArrayList<>();
        if (exchange.getIn().getBody() instanceof Iterable) {
            Iterable c = exchange.getIn().getBody(Iterable.class);
            for (Object o : c) {
                String object = (String) o;
                SendMessageBatchRequestEntry.Builder entry = SendMessageBatchRequestEntry.builder();
                entry.id(UUID.randomUUID().toString());
                entry.messageAttributes(translateAttributes(exchange.getIn().getHeaders(), exchange));
                entry.messageBody(object);
                addDelay(entry, exchange);
                configureFifoAttributes(entry, exchange);
                entries.add(entry.build());
            }
            request.entries(entries);
            SendMessageBatchResponse result = amazonSQS.sendMessageBatch(request.build());
            Message message = getMessageForResponse(exchange);
            message.setBody(result);
        } else if (exchange.getIn().getBody() instanceof String) {
            String c = exchange.getIn().getBody(String.class);
            String[] elements = c.split(getConfiguration().getBatchSeparator());
            for (String o : elements) {
                SendMessageBatchRequestEntry.Builder entry = SendMessageBatchRequestEntry.builder();
                entry.id(UUID.randomUUID().toString());
                entry.messageAttributes(translateAttributes(exchange.getIn().getHeaders(), exchange));
                entry.messageBody(o);
                addDelay(entry, exchange);
                configureFifoAttributes(entry, exchange);
                entries.add(entry.build());
            }
            request.entries(entries);
            SendMessageBatchResponse result = amazonSQS.sendMessageBatch(request.build());
            Message message = getMessageForResponse(exchange);
            message.setBody(result);
        } else {
            SendMessageBatchRequest req = exchange.getIn().getBody(SendMessageBatchRequest.class);
            SendMessageBatchResponse result = amazonSQS.sendMessageBatch(req);
            Message message = getMessageForResponse(exchange);
            message.setBody(result);
        }
    }

    private void deleteMessage(SqsClient amazonSQS, Exchange exchange) {
        String receiptHandle = exchange.getIn().getHeader(Sqs2Constants.RECEIPT_HANDLE, String.class);
        DeleteMessageRequest.Builder request = DeleteMessageRequest.builder();
        request.queueUrl(getQueueUrl());
        if (ObjectHelper.isEmpty(receiptHandle)) {
            throw new IllegalArgumentException("Receipt Handle must be specified for the operation deleteMessage");
        }
        request.receiptHandle(receiptHandle);
        DeleteMessageResponse result = amazonSQS.deleteMessage(request.build());
        Message message = getMessageForResponse(exchange);
        message.setBody(result);
    }

    private void listQueues(SqsClient amazonSQS, Exchange exchange) {
        ListQueuesRequest.Builder request = ListQueuesRequest.builder();
        if (ObjectHelper.isNotEmpty(exchange.getIn().getHeader(Sqs2Constants.SQS_QUEUE_PREFIX))) {
            request.queueNamePrefix(exchange.getIn().getHeader(Sqs2Constants.SQS_QUEUE_PREFIX, String.class));
        }
        ListQueuesResponse result = amazonSQS.listQueues(request.build());
        Message message = getMessageForResponse(exchange);
        message.setBody(result);
    }

    private void purgeQueue(SqsClient amazonSQS, Exchange exchange) {
        PurgeQueueRequest.Builder request = PurgeQueueRequest.builder();
        if (ObjectHelper.isNotEmpty(exchange.getIn().getHeader(Sqs2Constants.SQS_QUEUE_PREFIX))) {
            request.queueUrl(getQueueUrl());
        }
        PurgeQueueResponse result = amazonSQS.purgeQueue(request.build());
        Message message = getMessageForResponse(exchange);
        message.setBody(result);
    }

    private void deleteQueue(SqsClient amazonSQS, Exchange exchange) {
        DeleteQueueRequest.Builder request = DeleteQueueRequest.builder();
        if (ObjectHelper.isNotEmpty(exchange.getIn().getHeader(Sqs2Constants.SQS_QUEUE_PREFIX))) {
            request.queueUrl(getQueueUrl());
        }
        DeleteQueueResponse result = amazonSQS.deleteQueue(request.build());
        Message message = getMessageForResponse(exchange);
        message.setBody(result);
    }

    private void configureFifoAttributes(SendMessageRequest.Builder request, Exchange exchange) {
        if (getEndpoint().getConfiguration().isFifoQueue()) {
            // use strategies
            if (ObjectHelper.isNotEmpty(getEndpoint().getConfiguration().getMessageGroupIdStrategy())) {
                MessageGroupIdStrategy messageGroupIdStrategy = getEndpoint().getConfiguration().getMessageGroupIdStrategy();
                String messageGroupId = messageGroupIdStrategy.getMessageGroupId(exchange);
                request.messageGroupId(messageGroupId);
            }

            if (ObjectHelper.isNotEmpty(getEndpoint().getConfiguration().getMessageDeduplicationIdStrategy())) {
                MessageDeduplicationIdStrategy messageDeduplicationIdStrategy
                        = getEndpoint().getConfiguration().getMessageDeduplicationIdStrategy();
                String messageDeduplicationId = messageDeduplicationIdStrategy.getMessageDeduplicationId(exchange);
                request.messageDeduplicationId(messageDeduplicationId);
            }

        }
    }

    private void configureFifoAttributes(SendMessageBatchRequestEntry.Builder request, Exchange exchange) {
        if (getEndpoint().getConfiguration().isFifoQueue()) {
            // use strategies
            MessageGroupIdStrategy messageGroupIdStrategy = getEndpoint().getConfiguration().getMessageGroupIdStrategy();
            String messageGroupId = messageGroupIdStrategy.getMessageGroupId(exchange);
            request.messageGroupId(messageGroupId);

            MessageDeduplicationIdStrategy messageDeduplicationIdStrategy
                    = getEndpoint().getConfiguration().getMessageDeduplicationIdStrategy();
            String messageDeduplicationId = messageDeduplicationIdStrategy.getMessageDeduplicationId(exchange);
            request.messageDeduplicationId(messageDeduplicationId);

        }
    }

    private void addDelay(SendMessageRequest.Builder request, Exchange exchange) {
        Integer headerValue = exchange.getIn().getHeader(Sqs2Constants.DELAY_HEADER, Integer.class);
        Integer delayValue;
        if (headerValue == null) {
            LOG.trace("Using the config delay");
            delayValue = getEndpoint().getConfiguration().getDelaySeconds();
        } else {
            LOG.trace("Using the header delay");
            delayValue = headerValue;
        }
        LOG.trace("found delay: {}", delayValue);
        if (delayValue != null) {
            request.delaySeconds(delayValue);
        }
    }

    private void addDelay(SendMessageBatchRequestEntry.Builder request, Exchange exchange) {
        Integer headerValue = exchange.getIn().getHeader(Sqs2Constants.DELAY_HEADER, Integer.class);
        Integer delayValue;
        if (headerValue == null) {
            LOG.trace("Using the config delay");
            delayValue = getEndpoint().getConfiguration().getDelaySeconds();
        } else {
            LOG.trace("Using the header delay");
            delayValue = headerValue;
        }
        LOG.trace("found delay: {}", delayValue);
        if (delayValue != null) {
            request.delaySeconds(delayValue);
        }
    }

    protected SqsClient getClient() {
        return getEndpoint().getClient();
    }

    protected String getQueueUrl() {
        return getEndpoint().getQueueUrl();
    }

    protected Sqs2Configuration getConfiguration() {
        return getEndpoint().getConfiguration();
    }

    @Override
    public Sqs2Endpoint getEndpoint() {
        return (Sqs2Endpoint) super.getEndpoint();
    }

    @Override
    public String toString() {
        if (sqsProducerToString == null) {
            sqsProducerToString = "SqsProducer[" + URISupport.sanitizeUri(getEndpoint().getEndpointUri()) + "]";
        }
        return sqsProducerToString;
    }

    Map<String, MessageAttributeValue> translateAttributes(Map<String, Object> headers, Exchange exchange) {
        Map<String, MessageAttributeValue> result = new HashMap<>();
        HeaderFilterStrategy headerFilterStrategy = getEndpoint().getHeaderFilterStrategy();
        for (Entry<String, Object> entry : headers.entrySet()) {
            // only put the message header which is not filtered into the
            // message attribute
            if (!headerFilterStrategy.applyFilterToCamelHeaders(entry.getKey(), entry.getValue(), exchange)) {
                // We are going to put the first MAX_ATTRIBUTES headers, because this is the maximum Attributes an SQS Message could accept
                if (result.size() < MAX_ATTRIBUTES) {
                    MessageAttributeValue mav = Sqs2MessageHelper.toMessageAttributeValue(entry.getValue());
                    if (mav != null) {
                        result.put(entry.getKey(), mav);
                    } else {
                        String action = getConfiguration().getMessageHeaderExceededLimit();
                        if ("WARN".equalsIgnoreCase(action) || "WARN_ONCE".equalsIgnoreCase(action)) {
                            // cannot translate the message header to message attribute value
                            LOG.warn("Cannot put the message header key={}, value={} into SQS MessageAttribute", entry.getKey(),
                                    entry.getValue());
                            if ("WARN_ONCE".equalsIgnoreCase(action)) {
                                break;
                            }
                        } else if ("IGNORE".equalsIgnoreCase(action)) {
                            break;
                        } else if ("FAIL".equalsIgnoreCase(action)) {
                            throw new IllegalArgumentException(
                                    "Number of message headers exceeded. At most " + MAX_ATTRIBUTES
                                                               + " headers is allowed when sending to AWS SQS.");
                        }
                    }
                }
            }
        }
        return result;
    }

    public static Message getMessageForResponse(final Exchange exchange) {
        return exchange.getMessage();
    }

    private Sqs2Operations determineOperation(Exchange exchange) {
        Sqs2Operations operation = exchange.getIn().getHeader(Sqs2Constants.SQS_OPERATION, Sqs2Operations.class);
        if (operation == null) {
            operation = getConfiguration().getOperation();
        }
        return operation;
    }
}
