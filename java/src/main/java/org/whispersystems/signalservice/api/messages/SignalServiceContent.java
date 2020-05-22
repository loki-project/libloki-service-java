/*
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.whispersystems.signalservice.api.messages;

import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.loki.protocol.multidevice.DeviceLink;
import org.whispersystems.signalservice.loki.protocol.meta.LokiServiceMessage;

public class SignalServiceContent {
  private final String  sender;
  private final int     senderDevice;
  private final long    timestamp;
  private final boolean needsReceipt;

  // Loki
  private final boolean isFriendRequest;
  private final boolean isSessionRequest;
  private final boolean isSessionRestorationRequest;
  private final boolean isUnlinkingRequest;

  private Optional<SignalServiceDataMessage>          message;
  private Optional<SignalServiceSyncMessage>          synchronizeMessage;
  private final Optional<SignalServiceCallMessage>    callMessage;
  private final Optional<SignalServiceReceiptMessage> readMessage;
  private final Optional<SignalServiceTypingMessage>  typingMessage;

  // Loki
  private final Optional<DeviceLink> deviceLink;
  public Optional<LokiServiceMessage> lokiServiceMessage = Optional.absent();
  public Optional<String> senderDisplayName = Optional.absent();
  public Optional<String> senderProfilePictureURL = Optional.absent();

  public SignalServiceContent(LokiServiceMessage lokiServiceMessage, String sender, int senderDevice, long timestamp) {
    this.sender                      = sender;
    this.senderDevice                = senderDevice;
    this.timestamp                   = timestamp;
    this.needsReceipt                = false;
    this.isFriendRequest             = false;
    this.isSessionRequest            = false;
    this.isSessionRestorationRequest = false;
    this.message                     = Optional.absent();
    this.synchronizeMessage          = Optional.absent();
    this.callMessage                 = Optional.absent();
    this.readMessage                 = Optional.absent();
    this.typingMessage               = Optional.absent();
    this.deviceLink                  = Optional.absent();
    this.lokiServiceMessage          = Optional.fromNullable(lokiServiceMessage);
    this.isUnlinkingRequest          = false;
  }

  public SignalServiceContent(SignalServiceDataMessage message, String sender, int senderDevice, long timestamp, boolean needsReceipt, boolean isFriendRequest, boolean isSessionRequest, boolean isSessionRestorationRequest, boolean isUnlinkingRequest) {
    this.sender                      = sender;
    this.senderDevice                = senderDevice;
    this.timestamp                   = timestamp;
    this.needsReceipt                = needsReceipt;
    this.isFriendRequest             = isFriendRequest;
    this.isSessionRequest            = isSessionRequest;
    this.isSessionRestorationRequest = isSessionRestorationRequest;
    this.message                     = Optional.fromNullable(message);
    this.synchronizeMessage          = Optional.absent();
    this.callMessage                 = Optional.absent();
    this.readMessage                 = Optional.absent();
    this.typingMessage               = Optional.absent();
    this.deviceLink                  = Optional.absent();
    this.isUnlinkingRequest          = isUnlinkingRequest;
  }

  public SignalServiceContent(SignalServiceSyncMessage synchronizeMessage, String sender, int senderDevice, long timestamp) {
    this.sender                      = sender;
    this.senderDevice                = senderDevice;
    this.timestamp                   = timestamp;
    this.needsReceipt                = false;
    this.isFriendRequest             = false;
    this.isSessionRequest            = false;
    this.isSessionRestorationRequest = false;
    this.message                     = Optional.absent();
    this.synchronizeMessage          = Optional.fromNullable(synchronizeMessage);
    this.callMessage                 = Optional.absent();
    this.readMessage                 = Optional.absent();
    this.typingMessage               = Optional.absent();
    this.deviceLink                  = Optional.absent();
    this.isUnlinkingRequest          = false;
  }

  public SignalServiceContent(SignalServiceCallMessage callMessage, String sender, int senderDevice, long timestamp) {
    this.sender                      = sender;
    this.senderDevice                = senderDevice;
    this.timestamp                   = timestamp;
    this.needsReceipt                = false;
    this.isFriendRequest             = false;
    this.isSessionRequest            = false;
    this.isSessionRestorationRequest = false;
    this.message                     = Optional.absent();
    this.synchronizeMessage          = Optional.absent();
    this.callMessage                 = Optional.of(callMessage);
    this.readMessage                 = Optional.absent();
    this.typingMessage               = Optional.absent();
    this.deviceLink                  = Optional.absent();
    this.isUnlinkingRequest          = false;
  }

  public SignalServiceContent(SignalServiceReceiptMessage receiptMessage, String sender, int senderDevice, long timestamp) {
    this.sender                      = sender;
    this.senderDevice                = senderDevice;
    this.timestamp                   = timestamp;
    this.needsReceipt                = false;
    this.isFriendRequest             = false;
    this.isSessionRequest            = false;
    this.isSessionRestorationRequest = false;
    this.message                     = Optional.absent();
    this.synchronizeMessage          = Optional.absent();
    this.callMessage                 = Optional.absent();
    this.readMessage                 = Optional.of(receiptMessage);
    this.typingMessage               = Optional.absent();
    this.deviceLink                  = Optional.absent();
    this.isUnlinkingRequest          = false;
  }

  public SignalServiceContent(SignalServiceTypingMessage typingMessage, String sender, int senderDevice, long timestamp) {
    this.sender                      = sender;
    this.senderDevice                = senderDevice;
    this.timestamp                   = timestamp;
    this.needsReceipt                = false;
    this.isFriendRequest             = false;
    this.isSessionRequest            = false;
    this.isSessionRestorationRequest = false;
    this.message                     = Optional.absent();
    this.synchronizeMessage          = Optional.absent();
    this.callMessage                 = Optional.absent();
    this.readMessage                 = Optional.absent();
    this.typingMessage               = Optional.of(typingMessage);
    this.deviceLink                  = Optional.absent();
    this.isUnlinkingRequest          = false;
  }

  public SignalServiceContent(DeviceLink deviceLink, String sender, int senderDevice, long timestamp) {
    this.sender                      = sender;
    this.senderDevice                = senderDevice;
    this.timestamp                   = timestamp;
    this.needsReceipt                = false;
    this.isFriendRequest             = false;
    this.isSessionRequest            = false;
    this.isSessionRestorationRequest = false;
    this.message                     = Optional.absent();
    this.synchronizeMessage          = Optional.absent();
    this.callMessage                 = Optional.absent();
    this.readMessage                 = Optional.absent();
    this.typingMessage               = Optional.absent();
    this.deviceLink                  = Optional.fromNullable(deviceLink);
    this.isUnlinkingRequest          = false;
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
  public boolean isFriendRequest() { return isFriendRequest; }

  public boolean isSessionRequest() { return isSessionRequest; }

  public boolean isSessionRestorationRequest() { return isSessionRestorationRequest; }

  public boolean isUnlinkingRequest() { return isUnlinkingRequest; }

  public Optional<DeviceLink> getDeviceLink() { return deviceLink; }

  public void setLokiServiceMessage(LokiServiceMessage lokiServiceMessage) { this.lokiServiceMessage = Optional.fromNullable(lokiServiceMessage); }

  public void setSenderDisplayName(String displayName) { senderDisplayName = Optional.fromNullable(displayName); }

  public void setSenderProfilePictureURL(String url) { senderProfilePictureURL = Optional.fromNullable(url); }
}
