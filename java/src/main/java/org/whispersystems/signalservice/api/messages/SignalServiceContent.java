/*
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.whispersystems.signalservice.api.messages;

import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.loki.api.PairingAuthorisation;
import org.whispersystems.signalservice.loki.messaging.LokiServiceMessage;

public class SignalServiceContent {

  private final String  sender;
  private final int     senderDevice;
  private final long    timestamp;
  private final boolean needsReceipt;

  private Optional<SignalServiceDataMessage>          message;
  private Optional<SignalServiceSyncMessage>          synchronizeMessage;
  private final Optional<SignalServiceCallMessage>    callMessage;
  private final Optional<SignalServiceReceiptMessage> readMessage;
  private final Optional<SignalServiceTypingMessage>  typingMessage;

  // Loki
  private final Optional<PairingAuthorisation> pairingAuthorisation;
  public Optional<LokiServiceMessage> lokiServiceMessage = Optional.absent();
  public Optional<String> senderDisplayName = Optional.absent();
  public Optional<String> senderProfileAvatarUrl = Optional.absent();

  public SignalServiceContent(LokiServiceMessage lokiServiceMessage, String sender, int senderDevice, long timestamp, boolean needsReceipt) {
    this.sender       = sender;
    this.senderDevice = senderDevice;
    this.timestamp    = timestamp;
    this.needsReceipt = needsReceipt;

    this.message              = Optional.absent();
    this.synchronizeMessage   = Optional.absent();
    this.callMessage          = Optional.absent();
    this.readMessage          = Optional.absent();
    this.typingMessage        = Optional.absent();
    this.pairingAuthorisation = Optional.absent();
    this.lokiServiceMessage   = Optional.fromNullable(lokiServiceMessage);
  }

  public SignalServiceContent(SignalServiceDataMessage message, String sender, int senderDevice, long timestamp, boolean needsReceipt) {
    this.sender       = sender;
    this.senderDevice = senderDevice;
    this.timestamp    = timestamp;
    this.needsReceipt = needsReceipt;

    this.message            = Optional.fromNullable(message);
    this.synchronizeMessage = Optional.absent();
    this.callMessage        = Optional.absent();
    this.readMessage        = Optional.absent();
    this.typingMessage      = Optional.absent();
    this.pairingAuthorisation = Optional.absent();
  }

  public SignalServiceContent(SignalServiceSyncMessage synchronizeMessage, String sender, int senderDevice, long timestamp, boolean needsReceipt) {
    this.sender       = sender;
    this.senderDevice = senderDevice;
    this.timestamp    = timestamp;
    this.needsReceipt = needsReceipt;

    this.message            = Optional.absent();
    this.synchronizeMessage = Optional.fromNullable(synchronizeMessage);
    this.callMessage        = Optional.absent();
    this.readMessage        = Optional.absent();
    this.typingMessage      = Optional.absent();
    this.pairingAuthorisation = Optional.absent();
  }

  public SignalServiceContent(SignalServiceCallMessage callMessage, String sender, int senderDevice, long timestamp, boolean needsReceipt) {
    this.sender       = sender;
    this.senderDevice = senderDevice;
    this.timestamp    = timestamp;
    this.needsReceipt = needsReceipt;

    this.message            = Optional.absent();
    this.synchronizeMessage = Optional.absent();
    this.callMessage        = Optional.of(callMessage);
    this.readMessage        = Optional.absent();
    this.typingMessage      = Optional.absent();
    this.pairingAuthorisation = Optional.absent();
  }

  public SignalServiceContent(SignalServiceReceiptMessage receiptMessage, String sender, int senderDevice, long timestamp, boolean needsReceipt) {
    this.sender       = sender;
    this.senderDevice = senderDevice;
    this.timestamp    = timestamp;
    this.needsReceipt = needsReceipt;

    this.message            = Optional.absent();
    this.synchronizeMessage = Optional.absent();
    this.callMessage        = Optional.absent();
    this.readMessage        = Optional.of(receiptMessage);
    this.typingMessage      = Optional.absent();
    this.pairingAuthorisation = Optional.absent();
  }

  public SignalServiceContent(SignalServiceTypingMessage typingMessage, String sender, int senderDevice, long timestamp, boolean needsReceipt) {
    this.sender       = sender;
    this.senderDevice = senderDevice;
    this.timestamp    = timestamp;
    this.needsReceipt = needsReceipt;

    this.message            = Optional.absent();
    this.synchronizeMessage = Optional.absent();
    this.callMessage        = Optional.absent();
    this.readMessage        = Optional.absent();
    this.typingMessage      = Optional.of(typingMessage);
    this.pairingAuthorisation = Optional.absent();
  }

  public SignalServiceContent(PairingAuthorisation authorisation, String sender, int senderDevice, long timestamp, boolean needsReceipt) {
    this.sender       = sender;
    this.senderDevice = senderDevice;
    this.timestamp    = timestamp;
    this.needsReceipt = needsReceipt;

    this.message            = Optional.absent();
    this.synchronizeMessage = Optional.absent();
    this.callMessage        = Optional.absent();
    this.readMessage        = Optional.absent();
    this.typingMessage      = Optional.absent();
    this.pairingAuthorisation = Optional.fromNullable(authorisation);
  }

  public Optional<SignalServiceDataMessage> getDataMessage() {
    return message;
  }
  public void setDataMessage(SignalServiceDataMessage message) { this.message = Optional.fromNullable(message); }

  public Optional<SignalServiceSyncMessage> getSyncMessage() { return synchronizeMessage; }
  public void setSyncMessage(SignalServiceSyncMessage message) { this.synchronizeMessage = Optional.fromNullable(message); }

  public Optional<SignalServiceCallMessage> getCallMessage() {
    return callMessage;
  }

  public Optional<SignalServiceReceiptMessage> getReceiptMessage() {
    return readMessage;
  }

  public Optional<SignalServiceTypingMessage> getTypingMessage() {
    return typingMessage;
  }

  public Optional<PairingAuthorisation> getPairingAuthorisation() { return pairingAuthorisation; }

  public String getSender() {
    return sender;
  }

  public int getSenderDevice() {
    return senderDevice;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public boolean isNeedsReceipt() {
    return needsReceipt;
  }

  // Loki
  public void setLokiServiceMessage(LokiServiceMessage lokiServiceMessage) { this.lokiServiceMessage = Optional.fromNullable(lokiServiceMessage); }
  public void setSenderDisplayName(String displayName) { senderDisplayName = Optional.fromNullable(displayName); }
  public void setSenderProfileAvatarUrl(String url) { senderProfileAvatarUrl = Optional.fromNullable(url); }
}
