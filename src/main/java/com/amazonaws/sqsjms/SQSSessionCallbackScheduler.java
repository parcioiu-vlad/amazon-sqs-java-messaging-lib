/*
 * Copyright 2010-2014 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.sqsjms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.jms.JMSException;
import javax.jms.MessageListener;
import javax.jms.Session;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.amazonaws.sqsjms.SQSMessageConsumerPrefetch.MessageManager;
import com.amazonaws.sqsjms.SQSSession.CallbackEntry;
import com.amazonaws.sqsjms.acknowledge.AcknowledgeMode;
import com.amazonaws.sqsjms.acknowledge.Acknowledger;
import com.amazonaws.sqsjms.acknowledge.NegativeAcknowledger;
import com.amazonaws.sqsjms.acknowledge.SQSMessageIdentifier;

public class SQSSessionCallbackScheduler implements Runnable {
    private static final Log LOG = LogFactory.getLog(SQSSessionCallbackScheduler.class);
    
    private final AtomicLong changeMessageVisibilityIdGenerator = new AtomicLong();

    protected ArrayDeque<CallbackEntry> callbackQueue;

    private AcknowledgeMode acknowledgeMode;

    private SQSSession session;
    
    protected NegativeAcknowledger negativeAcknowledger;
    
    private final Acknowledger acknowledger;
    
    // Only set from the callback thread 
    private SQSMessageConsumer consumerCloseAfterCallback;
    
    private volatile boolean closed = false;
    
    SQSSessionCallbackScheduler(SQSSession session, AcknowledgeMode acknowledgeMode, Acknowledger acknowledger) {
        this.session = session;
        this.acknowledgeMode = acknowledgeMode;
        callbackQueue = new ArrayDeque<CallbackEntry>();
        negativeAcknowledger = new NegativeAcknowledger(
                this.session.getParentConnection().getWrappedAmazonSQSClient(), changeMessageVisibilityIdGenerator);
        this.acknowledger = acknowledger;
    }
    
    /**
     * Used in case no consumers have started, and session needs to terminate
     * the thread
     */
    void close() {
        closed = true;
    }
    
    @Override
    public void run() {
        CallbackEntry callbackEntry = null;
        try {            
            while (true) {
                try {
                    if (closed) {
                        break;
                    }
                    synchronized (callbackQueue) {
                        callbackEntry = callbackQueue.pollFirst();
                    }

                    if (callbackEntry == null) {
                        continue;
                    }

                    MessageListener messageListener = callbackEntry.getMessageListener();
                    MessageManager messageManager = callbackEntry.getMessageManager();
                    SQSMessage message = (SQSMessage) messageManager.getMessage();
                    
                    boolean retryOnInterruption = false;
                    boolean exit = false;
                    do {
                        try {
                            // this takes care of start and stop
                            session.startingCallback(messageManager.getPrefetchManager().getMessageConsumer());
                        } catch (InterruptedException e) {
                            retryOnInterruption = true;
                        } catch (JMSException e) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Not running callback: " + e.getMessage());
                            }
                            exit = true;
                            break;
                        }
                    } while (retryOnInterruption);
                    
                    if (exit) {
                        break;
                    }

                    try {
                        /**
                         * Notifying consumer prefetch thread so that it can
                         * continue to prefetch
                         */
                        messageManager.getPrefetchManager().messageDispatched();
                        int ackMode = acknowledgeMode.getOriginalAcknowledgeMode();
                        boolean tryNack = true;
                        try {
                            if (messageListener != null) {
                                if (ackMode != Session.AUTO_ACKNOWLEDGE) {
                                    acknowledger.notifyMessageReceived(message);
                                }
                                boolean callbackFailed = false;
                                try {
                                    messageListener.onMessage(message);
                                } catch (Throwable ex) {
                                    LOG.info("Exception thrown from onMessage callback for message " +
                                             message.getSQSMessageId(), ex);
                                    callbackFailed = true;
                                } finally {
                                    if (!callbackFailed) {
                                        if (ackMode == Session.AUTO_ACKNOWLEDGE) {
                                            message.acknowledge();
                                        }
                                        tryNack = false;
                                    }
                                }
                            }
                        } catch (JMSException ex) {
                            LOG.warn(
                                    "Unable to complete message dispatch for the message " +
                                            message.getSQSMessageId(), ex);
                        } finally {
                            if (tryNack) {
                                nackReceivedMessage(message);
                            }
                        }

                        /**
                         * The consumer close is delegated to the session thread
                         * if consumer close is called by its message listener's
                         * onMessage method on its own consumer.
                         */
                        if (consumerCloseAfterCallback != null) {
                            consumerCloseAfterCallback.doClose();
                            consumerCloseAfterCallback = null;
                        }
                    } finally {
                        session.finishedCallback();
                    }
                } catch (Throwable ex) {
                    LOG.error("Unexpected exception thrown during the run of the scheduled callback", ex);
                }
            }
        } finally {
            if (callbackEntry != null) {
                nackReceivedMessage((SQSMessage) callbackEntry.getMessageManager().getMessage());
            }
            nackQueuedMessages();
            callbackQueue.clear();
        }
    }
    
    public void setConsumerCloseAfterCallback(SQSMessageConsumer messageConsumer) {
        consumerCloseAfterCallback = messageConsumer;
    }
    
    void scheduleCallBack(MessageListener messageListener, MessageManager messageManager) {
        CallbackEntry callbackEntry = new CallbackEntry(messageListener, messageManager);
        
        synchronized (callbackQueue) {
            callbackQueue.push(callbackEntry);
        }
    }
            
    protected void nackQueuedMessages() {
        synchronized (callbackQueue) {
            try {
                List<SQSMessageIdentifier> nackMessageIdentifiers = new ArrayList<SQSMessageIdentifier>();
                while (!callbackQueue.isEmpty()) {
                    SQSMessage nackMessage = (SQSMessage) callbackQueue.pollFirst().getMessageManager().getMessage();
                    nackMessageIdentifiers.add(new SQSMessageIdentifier(
                            nackMessage.getQueueUrl(), nackMessage.getReceiptHandle(),
                            nackMessage.getSQSMessageId()));
                }

                if (!nackMessageIdentifiers.isEmpty()) {
                    negativeAcknowledger.bulkAction(nackMessageIdentifiers, nackMessageIdentifiers.size());
                }
            } catch (JMSException e) {
                LOG.warn("Caught exception while nacking the remaining messages on session callback queue", e);
            }
        }
    }

    private void nackReceivedMessage(SQSMessage message) {
        try {
            negativeAcknowledger.action(
                    message.getQueueUrl(), Collections.singletonList(message.getReceiptHandle()));
        } catch (JMSException e) {
            LOG.warn("Unable to nack the message " + message.getSQSMessageId(), e);
        }
    }
}