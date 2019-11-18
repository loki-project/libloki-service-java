/*
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */
package org.whispersystems.signalservice.api;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.jetbrains.annotations.Nullable;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.crypto.AttachmentCipherOutputStream;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccessPair;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage;
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage;
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage;
import org.whispersystems.signalservice.api.messages.calls.OfferMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.api.messages.multidevice.BlockedListMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ConfigurationMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.messages.multidevice.StickerPackOperationMessage;
import org.whispersystems.signalservice.api.messages.multidevice.VerifiedMessage;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream;
import org.whispersystems.signalservice.internal.push.MismatchedDevices;
import org.whispersystems.signalservice.internal.push.OutgoingPushMessage;
import org.whispersystems.signalservice.internal.push.OutgoingPushMessageList;
import org.whispersystems.signalservice.internal.push.PushAttachmentData;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentPointer;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.LokiProfile;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.NullMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.PrekeyBundleMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.TypingMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Verified;
import org.whispersystems.signalservice.internal.push.StaleDevices;
import org.whispersystems.signalservice.internal.push.http.AttachmentCipherOutputStreamFactory;
import org.whispersystems.signalservice.internal.push.http.OutputStreamFactory;
import org.whispersystems.signalservice.internal.util.Base64;
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider;
import org.whispersystems.signalservice.internal.util.Util;
import org.whispersystems.signalservice.internal.util.concurrent.SettableFuture;
import org.whispersystems.signalservice.loki.messaging.LokiSyncMessage;
import org.whispersystems.signalservice.loki.utilities.BasicOutputStreamFactory;
import org.whispersystems.signalservice.loki.api.LokiAPI;
import org.whispersystems.signalservice.loki.api.LokiAPIDatabaseProtocol;
import org.whispersystems.signalservice.loki.api.LokiAttachmentAPI;
import org.whispersystems.signalservice.loki.api.LokiPublicChat;
import org.whispersystems.signalservice.loki.api.LokiPublicChatAPI;
import org.whispersystems.signalservice.loki.api.LokiPublicChatMessage;
import org.whispersystems.signalservice.loki.api.LokiStorageAPI;
import org.whispersystems.signalservice.loki.api.PairingAuthorisation;
import org.whispersystems.signalservice.loki.crypto.LokiServiceCipher;
import org.whispersystems.signalservice.loki.messaging.LokiMessageDatabaseProtocol;
import org.whispersystems.signalservice.loki.messaging.LokiPreKeyBundleDatabaseProtocol;
import org.whispersystems.signalservice.loki.messaging.LokiSessionDatabaseProtocol;
import org.whispersystems.signalservice.loki.messaging.LokiThreadDatabaseProtocol;
import org.whispersystems.signalservice.loki.messaging.LokiThreadSessionResetStatus;
import org.whispersystems.signalservice.loki.messaging.LokiUserDatabaseProtocol;
import org.whispersystems.signalservice.loki.messaging.SignalMessageInfo;
import org.whispersystems.signalservice.loki.utilities.Analytics;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.Triple;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import nl.komponents.kovenant.Promise;

/**
 * The main interface for sending Signal Service messages.
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceMessageSender {

  private static final String TAG = SignalServiceMessageSender.class.getSimpleName();

  private final PushServiceSocket                                   socket;
  private final SignalProtocolStore                                 store;
  private final SignalServiceAddress                                localAddress;
  private final Optional<EventListener>                             eventListener;

  private final AtomicReference<Optional<SignalServiceMessagePipe>> pipe;
  private final AtomicReference<Optional<SignalServiceMessagePipe>> unidentifiedPipe;
  private final AtomicBoolean                                       isMultiDevice;

  private final String                                              userHexEncodedPublicKey;
  private final LokiAPIDatabaseProtocol                             apiDatabase;
  private final LokiThreadDatabaseProtocol                          threadDatabase;
  private final LokiMessageDatabaseProtocol                         messageDatabase;
  private final LokiPreKeyBundleDatabaseProtocol                    preKeyBundleDatabase;
  private final LokiSessionDatabaseProtocol                         sessionDatabase;
  private final LokiUserDatabaseProtocol                            userDatabase;

  /**
   * Construct a SignalServiceMessageSender.
   *
   * @param urls The URL of the Signal Service.
   * @param user The Signal Service username (eg phone number).
   * @param password The Signal Service user password.
   * @param store The SignalProtocolStore.
   * @param eventListener An optional event listener, which fires whenever sessions are
   *                      setup or torn down for a recipient.
   */
  public SignalServiceMessageSender(SignalServiceConfiguration urls,
                                    String user, String password,
                                    SignalProtocolStore store,
                                    String userAgent,
                                    boolean isMultiDevice,
                                    Optional<SignalServiceMessagePipe> pipe,
                                    Optional<SignalServiceMessagePipe> unidentifiedPipe,
                                    Optional<EventListener> eventListener,
                                    String userHexEncodedPublicKey,
                                    LokiAPIDatabaseProtocol apiDatabase,
                                    LokiThreadDatabaseProtocol threadDatabase,
                                    LokiMessageDatabaseProtocol messageDatabase,
                                    LokiPreKeyBundleDatabaseProtocol preKeyBundleDatabase,
                                    LokiSessionDatabaseProtocol sessionDatabase,
                                    LokiUserDatabaseProtocol userDatabase)
  {
    this(urls, new StaticCredentialsProvider(user, password, null), store, userAgent, isMultiDevice, pipe, unidentifiedPipe, eventListener, userHexEncodedPublicKey, apiDatabase, threadDatabase, messageDatabase, preKeyBundleDatabase, sessionDatabase, userDatabase);
  }

  public SignalServiceMessageSender(SignalServiceConfiguration urls,
                                    CredentialsProvider credentialsProvider,
                                    SignalProtocolStore store,
                                    String userAgent,
                                    boolean isMultiDevice,
                                    Optional<SignalServiceMessagePipe> pipe,
                                    Optional<SignalServiceMessagePipe> unidentifiedPipe,
                                    Optional<EventListener> eventListener,
                                    String userHexEncodedPublicKey,
                                    LokiAPIDatabaseProtocol apiDatabase,
                                    LokiThreadDatabaseProtocol threadDatabase,
                                    LokiMessageDatabaseProtocol messageDatabase,
                                    LokiPreKeyBundleDatabaseProtocol preKeyBundleDatabase,
                                    LokiSessionDatabaseProtocol sessionDatabase,
                                    LokiUserDatabaseProtocol userDatabase)
  {
    this.socket               = new PushServiceSocket(urls, credentialsProvider, userAgent);
    this.store                = store;
    this.localAddress         = new SignalServiceAddress(credentialsProvider.getUser());
    this.pipe                 = new AtomicReference<Optional<SignalServiceMessagePipe>>(pipe);
    this.unidentifiedPipe     = new AtomicReference<Optional<SignalServiceMessagePipe>>(unidentifiedPipe);
    this.isMultiDevice        = new AtomicBoolean(isMultiDevice);
    this.eventListener        = eventListener;
    this.userHexEncodedPublicKey = userHexEncodedPublicKey;
    this.apiDatabase          = apiDatabase;
    this.threadDatabase       = threadDatabase;
    this.messageDatabase      = messageDatabase;
    this.preKeyBundleDatabase = preKeyBundleDatabase;
    this.sessionDatabase      = sessionDatabase;
    this.userDatabase         = userDatabase;
  }

  /**
   * Send a read receipt for a received message.
   *
   * @param recipient The sender of the received message you're acknowledging.
   * @param message The read receipt to deliver.
   * @throws IOException
   * @throws UntrustedIdentityException
   */
  public void sendReceipt(long messageID,
                          SignalServiceAddress recipient,
                          Optional<UnidentifiedAccessPair> unidentifiedAccess,
                          SignalServiceReceiptMessage message)
      throws IOException, UntrustedIdentityException
  {
    byte[] content = createReceiptContent(message);

    sendMessage(messageID, recipient, getTargetUnidentifiedAccess(unidentifiedAccess), message.getWhen(), content, false, message.getTTL());
  }

  /**
   * Send a typing indicator.
   *
   * @param recipient The destination
   * @param message The typing indicator to deliver
   * @throws IOException
   * @throws UntrustedIdentityException
   */
  public void sendTyping(long messageID,
                         SignalServiceAddress recipient,
                         Optional<UnidentifiedAccessPair> unidentifiedAccess,
                         SignalServiceTypingMessage message)
      throws IOException, UntrustedIdentityException
  {
    byte[] content = createTypingContent(message);

    sendMessage(messageID, recipient, getTargetUnidentifiedAccess(unidentifiedAccess), message.getTimestamp(), content, true, message.getTTL());
  }

  public void sendTyping(long                                   messageID,
                         List<SignalServiceAddress>             recipients,
                         List<Optional<UnidentifiedAccessPair>> unidentifiedAccess,
                         SignalServiceTypingMessage             message)
      throws IOException
  {
    byte[] content = createTypingContent(message);
    sendMessage(messageID, recipients, getTargetUnidentifiedAccess(unidentifiedAccess), message.getTimestamp(), content, true, message.getTTL());
  }


  /**
   * Send a call setup message to a single recipient.
   *
   * @param recipient The message's destination.
   * @param message The call message.
   * @throws IOException
   */
  public void sendCallMessage(long messageID,
                              SignalServiceAddress recipient,
                              Optional<UnidentifiedAccessPair> unidentifiedAccess,
                              SignalServiceCallMessage message)
      throws IOException, UntrustedIdentityException
  {
    byte[] content = createCallContent(message);
    sendMessage(messageID, recipient, getTargetUnidentifiedAccess(unidentifiedAccess), System.currentTimeMillis(), content, false, message.getTTL());
  }

  public SendMessageResult sendMessage(long                             messageID,
                                       SignalServiceAddress             recipient,
                                       Optional<UnidentifiedAccessPair> unidentifiedAccess,
                                       SignalServiceDataMessage         message)
          throws UntrustedIdentityException, IOException
  {
    return sendMessage(messageID, recipient, unidentifiedAccess, message, Optional.<LokiSyncMessage>absent());
  }

  /**
   * Send a message to a single recipient.
   *
   * @param recipient The message's destination.
   * @param message The message.
   * @throws UntrustedIdentityException
   * @throws IOException
   */
  public SendMessageResult sendMessage(long                             messageID,
                                       SignalServiceAddress             recipient,
                                       Optional<UnidentifiedAccessPair> unidentifiedAccess,
                                       SignalServiceDataMessage         message,
                                       Optional<LokiSyncMessage>        lokiSyncMessage)
      throws UntrustedIdentityException, IOException
  {
    byte[]            content   = createMessageContent(message, recipient);
    long              timestamp = message.getTimestamp();
    SendMessageResult result    = sendMessage(messageID, recipient, getTargetUnidentifiedAccess(unidentifiedAccess), timestamp, content, false, message.getTTL(), message.isFriendRequest());

    if (lokiSyncMessage.isPresent() && (result.getSuccess() != null && message.canSyncMessage() || (unidentifiedAccess.isPresent() && isMultiDevice.get()))) {
      byte[] syncMessage = createMultiDeviceSentTranscriptContent(content, Optional.of(lokiSyncMessage.get().getRecipient()), timestamp, Collections.singletonList(result));
      // Trigger an event to send a sync message
      if (eventListener.isPresent()) {
        eventListener.get().onSyncEvent(lokiSyncMessage.get().getOriginalMessageID(), timestamp, syncMessage, message.getTTL());
      }
    }

    // Loki - Start session reset if needed
    if (message.isEndSession()) {
      sessionDatabase.archiveAllSessions(recipient.getNumber());

      long threadID = threadDatabase.getThreadID(recipient.getNumber());
      LokiThreadSessionResetStatus sessionResetStatus = threadDatabase.getSessionResetStatus(threadID);

      if (sessionResetStatus != LokiThreadSessionResetStatus.REQUEST_RECEIVED) {
        Log.d("Loki", "Starting session reset...");
        threadDatabase.setSessionResetStatus(threadID, LokiThreadSessionResetStatus.IN_PROGRESS);
      }
      
      if (eventListener.isPresent()) {
        eventListener.get().onSecurityEvent(recipient);
      }
    }

    return result;
  }

  /**
   * Send a message to a group.
   *
   * @param recipients The group members.
   * @param message The group message.
   * @throws IOException
   */
  public List<SendMessageResult> sendMessage(long                                   messageID,
                                             List<SignalServiceAddress>             recipients,
                                             List<Optional<UnidentifiedAccessPair>> unidentifiedAccess,
                                             SignalServiceDataMessage               message)
      throws IOException, UntrustedIdentityException
  {
    byte[]                  content            = createMessageContent(message, recipients.get(0));
    long                    timestamp          = message.getTimestamp();
    List<SendMessageResult> results            = sendMessage(messageID, recipients, getTargetUnidentifiedAccess(unidentifiedAccess), timestamp, content, false, message.getTTL());
    boolean                 needsSyncInResults = false;

    for (SendMessageResult result : results) {
      if (result.getSuccess() != null && result.getSuccess().isNeedsSync()) {
        needsSyncInResults = true;
        break;
      }
    }

    if (needsSyncInResults || (isMultiDevice.get())) {
      byte[] syncMessage = createMultiDeviceSentTranscriptContent(content, Optional.<SignalServiceAddress>absent(), timestamp, results);
      sendMessage(messageID, localAddress, Optional.<UnidentifiedAccess>absent(), timestamp, syncMessage, false, message.getTTL());
    }

    return results;
  }

  /**
   * Send a sync message to a specific recipient
   */
  public void sendMessage(long messageID, SignalServiceSyncMessage message, SignalServiceAddress recipient)
          throws IOException, UntrustedIdentityException
  {
    sendMessage(messageID, message, Optional.<UnidentifiedAccessPair>absent(), Optional.fromNullable(recipient));
  }

  /**
   * Send a sync message to all linked devices
   */
  public void sendMessage(long messageID, SignalServiceSyncMessage message, Optional<UnidentifiedAccessPair> unidentifiedAccess)
      throws IOException, UntrustedIdentityException
  {
    sendMessage(messageID, message, unidentifiedAccess, Optional.<SignalServiceAddress>absent());
  }

  private void sendMessage(long messageID, SignalServiceSyncMessage message, Optional<UnidentifiedAccessPair> unidentifiedAccess, Optional<SignalServiceAddress> recipient)
          throws IOException, UntrustedIdentityException
  {
    byte[] content;
    long timestamp = System.currentTimeMillis();

    if (message.getContacts().isPresent()) {
      content = createMultiDeviceContactsContent(message.getContacts().get().getContactsStream().asStream(),
                                                 message.getContacts().get().isComplete());
    } else if (message.getGroups().isPresent()) {
      content = createMultiDeviceGroupsContent(message.getGroups().get().asStream());
    } else if (message.getRead().isPresent()) {
      content = createMultiDeviceReadContent(message.getRead().get());
    } else if (message.getBlockedList().isPresent()) {
      content = createMultiDeviceBlockedContent(message.getBlockedList().get());
    } else if (message.getConfiguration().isPresent()) {
      content = createMultiDeviceConfigurationContent(message.getConfiguration().get());
    } else if (message.getSent().isPresent()) {
      timestamp = message.getSent().get().getTimestamp();
      content = createMultiDeviceSentTranscriptContent(message.getSent().get(), unidentifiedAccess);
    } else if (message.getStickerPackOperations().isPresent()) {
      content = createMultiDeviceStickerPackOperationContent(message.getStickerPackOperations().get());
    } else if (message.getVerified().isPresent()) {
      sendMessage(messageID, message.getVerified().get(), unidentifiedAccess);
      return;
    } else {
      throw new IOException("Unsupported sync message!");
    }

    // Loki - Trigger an event to send sync message
    if (recipient.isPresent()) {
      sendMessage(messageID, recipient.get(), Optional.<UnidentifiedAccess>absent(), System.currentTimeMillis(), content, false, message.getTTL());
    } else if (eventListener.isPresent()) {
      eventListener.get().onSyncEvent(messageID, timestamp, content, message.getTTL());
    }
  }

  public void setSoTimeoutMillis(long soTimeoutMillis) {
    socket.setSoTimeoutMillis(soTimeoutMillis);
  }

  public void cancelInFlightRequests() {
    socket.cancelInFlightRequests();
  }

  public void setMessagePipe(SignalServiceMessagePipe pipe, SignalServiceMessagePipe unidentifiedPipe) {
    this.pipe.set(Optional.fromNullable(pipe));
    this.unidentifiedPipe.set(Optional.fromNullable(unidentifiedPipe));
  }

  public void setIsMultiDevice(boolean isMultiDevice) {
    this.isMultiDevice.set(isMultiDevice);
  }

  public SignalServiceAttachmentPointer uploadAttachment(SignalServiceAttachmentStream attachment, boolean usePadding, @Nullable SignalServiceAddress recipient) throws IOException {
    boolean shouldUseEncryption = true;
    String server = LokiStorageAPI.shared.getServer();

    // Check if we are sending to a public chat
    if (recipient != null) {
      long threadID = threadDatabase.getThreadID(recipient.getNumber());
      LokiPublicChat publicChat = threadDatabase.getPublicChat(threadID);
      if (publicChat != null) {
        shouldUseEncryption = false;
        server = publicChat.getServer();
      }
    }

    byte[]             attachmentKey    = Util.getSecretBytes(64);
    long               paddedLength     = usePadding ? PaddingInputStream.getPaddedSize(attachment.getLength()) : attachment.getLength();
    InputStream        dataStream       = usePadding ? new PaddingInputStream(attachment.getInputStream(), attachment.getLength()) : attachment.getInputStream();
    long               ciphertextLength = shouldUseEncryption ? AttachmentCipherOutputStream.getCiphertextLength(paddedLength) : attachment.getLength();

    OutputStreamFactory outputStreamFactory = shouldUseEncryption ? new AttachmentCipherOutputStreamFactory(attachmentKey) : new BasicOutputStreamFactory();
    PushAttachmentData attachmentData   = new PushAttachmentData(attachment.getContentType(), dataStream, ciphertextLength, outputStreamFactory, attachment.getListener());

    // Loki - Upload attachment
    Triple<Long, String, byte[]> attachmentIdAndUrlAndDigest = LokiAttachmentAPI.INSTANCE.uploadAttachment(server, attachmentData);
    return new SignalServiceAttachmentPointer(attachmentIdAndUrlAndDigest.getFirst(),
                                              attachment.getContentType(),
                                              attachmentKey,
                                              Optional.of(Util.toIntExact(attachment.getLength())),
                                              attachment.getPreview(),
                                              attachment.getWidth(), attachment.getHeight(),
                                              Optional.of(attachmentIdAndUrlAndDigest.getThird()),
                                              attachment.getFileName(),
                                              attachment.getVoiceNote(),
                                              attachment.getCaption(), attachmentIdAndUrlAndDigest.getSecond());
  }

  private void sendMessage(long messageID, VerifiedMessage message, Optional<UnidentifiedAccessPair> unidentifiedAccess)
      throws IOException, UntrustedIdentityException
  {
    byte[] nullMessageBody = DataMessage.newBuilder()
                                        .setBody(Base64.encodeBytes(Util.getRandomLengthBytes(140)))
                                        .build()
                                        .toByteArray();

    NullMessage nullMessage = NullMessage.newBuilder()
                                         .setPadding(ByteString.copyFrom(nullMessageBody))
                                         .build();

    byte[] content          = Content.newBuilder()
                                     .setNullMessage(nullMessage)
                                     .build()
                                     .toByteArray();

    SendMessageResult result = sendMessage(messageID, new SignalServiceAddress(message.getDestination()), getTargetUnidentifiedAccess(unidentifiedAccess), message.getTimestamp(), content, false, message.getTTL());

    if (result.getSuccess().isNeedsSync()) {
      byte[] syncMessage = createMultiDeviceVerifiedContent(message, nullMessage.toByteArray());
      sendMessage(messageID, localAddress, Optional.<UnidentifiedAccess>absent(), message.getTimestamp(), syncMessage, false, message.getTTL());
    }
  }

  private byte[] createTypingContent(SignalServiceTypingMessage message) {
    Content.Builder       container = Content.newBuilder();
    TypingMessage.Builder builder   = TypingMessage.newBuilder();

    builder.setTimestamp(message.getTimestamp());

    if      (message.isTypingStarted()) builder.setAction(TypingMessage.Action.STARTED);
    else if (message.isTypingStopped()) builder.setAction(TypingMessage.Action.STOPPED);
    else                                throw new IllegalArgumentException("Unknown typing indicator");

    if (message.getGroupId().isPresent()) {
      builder.setGroupId(ByteString.copyFrom(message.getGroupId().get()));
    }

    return container.setTypingMessage(builder).build().toByteArray();
  }

  private byte[] createReceiptContent(SignalServiceReceiptMessage message) {
    Content.Builder        container = Content.newBuilder();
    ReceiptMessage.Builder builder   = ReceiptMessage.newBuilder();

    for (long timestamp : message.getTimestamps()) {
      builder.addTimestamp(timestamp);
    }

    if      (message.isDeliveryReceipt()) builder.setType(ReceiptMessage.Type.DELIVERY);
    else if (message.isReadReceipt())     builder.setType(ReceiptMessage.Type.READ);

    return container.setReceiptMessage(builder).build().toByteArray();
  }

  private byte[] createMessageContent(SignalServiceDataMessage message, SignalServiceAddress recipient) throws IOException {
    Content.Builder         container = Content.newBuilder();

    // Loki - Set the pre key bundle if needed
    if (message.getPreKeyBundle().isPresent()) {
      PreKeyBundle preKeyBundle = message.getPreKeyBundle().get();
      PrekeyBundleMessage.Builder preKeyBuilder = PrekeyBundleMessage.newBuilder()
              .setDeviceId(preKeyBundle.getDeviceId())
              .setIdentityKey(ByteString.copyFrom(preKeyBundle.getIdentityKey().serialize()))
              .setPreKeyId(preKeyBundle.getPreKeyId())
              .setPreKey(ByteString.copyFrom(preKeyBundle.getPreKey().serialize()))
              .setSignedKeyId(preKeyBundle.getSignedPreKeyId())
              .setSignedKey(ByteString.copyFrom(preKeyBundle.getSignedPreKey().serialize()))
              .setSignature(ByteString.copyFrom(preKeyBundle.getSignedPreKeySignature()))
              .setIdentityKey(ByteString.copyFrom(preKeyBundle.getIdentityKey().serialize()));
      container.setPreKeyBundleMessage(preKeyBuilder);
    }

    // Loki - Set the pairing authorisation if needed
    if (message.getPairingAuthorisation().isPresent()) {
      PairingAuthorisation authorisation = message.getPairingAuthorisation().get();
      SignalServiceProtos.PairingAuthorisationMessage.Builder builder = SignalServiceProtos.PairingAuthorisationMessage.newBuilder()
              .setPrimaryDevicePublicKey(authorisation.getPrimaryDevicePublicKey())
              .setSecondaryDevicePublicKey(authorisation.getSecondaryDevicePublicKey());
      if (authorisation.getRequestSignature() != null) { builder.setRequestSignature(ByteString.copyFrom(authorisation.getRequestSignature())); }
      if (authorisation.getGrantSignature() != null) { builder.setGrantSignature(ByteString.copyFrom(authorisation.getGrantSignature())); }
      container.setPairingAuthorisation(builder);
    }

    boolean hasDataContent = false;
    DataMessage.Builder builder = DataMessage.newBuilder();
    List<AttachmentPointer> pointers = createAttachmentPointers(message.getAttachments(), recipient);

    if (!pointers.isEmpty()) {
      builder.addAllAttachments(pointers);
      hasDataContent = true;
    }

    if (message.getBody().isPresent()) {
      builder.setBody(message.getBody().get());
      hasDataContent = true;
    }

    if (message.getGroupInfo().isPresent()) {
      builder.setGroup(createGroupContent(message.getGroupInfo().get(), recipient));
      hasDataContent = true;
    }

    if (message.isEndSession()) {
      builder.setFlags(DataMessage.Flags.END_SESSION_VALUE);
      hasDataContent = true;
    }

    if (message.isExpirationUpdate()) {
      builder.setFlags(DataMessage.Flags.EXPIRATION_TIMER_UPDATE_VALUE);
      hasDataContent = true;
    }

    if (message.isProfileKeyUpdate()) {
      builder.setFlags(DataMessage.Flags.PROFILE_KEY_UPDATE_VALUE);
      hasDataContent = true;
    }

    if (message.getExpiresInSeconds() > 0) {
      builder.setExpireTimer(message.getExpiresInSeconds());
      hasDataContent = true;
    }

    if (message.getProfileKey().isPresent()) {
      builder.setProfileKey(ByteString.copyFrom(message.getProfileKey().get()));
      hasDataContent = true;
    }

    if (message.getQuote().isPresent()) {
      DataMessage.Quote.Builder quoteBuilder = DataMessage.Quote.newBuilder()
              .setId(message.getQuote().get().getId())
              .setAuthor(message.getQuote().get().getAuthor().getNumber())
              .setText(message.getQuote().get().getText());

      for (SignalServiceDataMessage.Quote.QuotedAttachment attachment : message.getQuote().get().getAttachments()) {
        DataMessage.Quote.QuotedAttachment.Builder quotedAttachment = DataMessage.Quote.QuotedAttachment.newBuilder();

        quotedAttachment.setContentType(attachment.getContentType());

        if (attachment.getFileName() != null) {
          quotedAttachment.setFileName(attachment.getFileName());
        }

        if (attachment.getThumbnail() != null) {
          quotedAttachment.setThumbnail(createAttachmentPointer(attachment.getThumbnail().asStream(), recipient));
        }

        quoteBuilder.addAttachments(quotedAttachment);
      }

      builder.setQuote(quoteBuilder);
      hasDataContent = true;
    }

    if (message.getSharedContacts().isPresent()) {
      builder.addAllContact(createSharedContactContent(message.getSharedContacts().get(), recipient));
      hasDataContent = true;
    }

    if (message.getPreviews().isPresent()) {
      for (SignalServiceDataMessage.Preview preview : message.getPreviews().get()) {
        DataMessage.Preview.Builder previewBuilder = DataMessage.Preview.newBuilder();
        previewBuilder.setTitle(preview.getTitle());
        previewBuilder.setUrl(preview.getUrl());

        if (preview.getImage().isPresent()) {
          if (preview.getImage().get().isStream()) {
            previewBuilder.setImage(createAttachmentPointer(preview.getImage().get().asStream(), recipient));
          } else {
            previewBuilder.setImage(createAttachmentPointer(preview.getImage().get().asPointer()));
          }
        }

        builder.addPreview(previewBuilder.build());
        hasDataContent = true;
      }
    }

    if (message.getSticker().isPresent()) {
      DataMessage.Sticker.Builder stickerBuilder = DataMessage.Sticker.newBuilder();

      stickerBuilder.setPackId(ByteString.copyFrom(message.getSticker().get().getPackId()));
      stickerBuilder.setPackKey(ByteString.copyFrom(message.getSticker().get().getPackKey()));
      stickerBuilder.setStickerId(message.getSticker().get().getStickerId());

      if (message.getSticker().get().getAttachment().isStream()) {
        stickerBuilder.setData(createAttachmentPointer(message.getSticker().get().getAttachment().asStream(), true, recipient));
      } else {
        stickerBuilder.setData(createAttachmentPointer(message.getSticker().get().getAttachment().asPointer()));
      }

      builder.setSticker(stickerBuilder.build());
      hasDataContent = true;
    }

    if (hasDataContent) {
      builder.setTimestamp(message.getTimestamp());

      String displayName = userDatabase.getDisplayName(userHexEncodedPublicKey);
      if (displayName != null) {
        LokiProfile profile = LokiProfile.newBuilder().setDisplayName(displayName).build();
        builder.setProfile(profile);
      }

      container.setDataMessage(builder);
    } else if (!message.getPairingAuthorisation().isPresent()) {
      // Loki - Set an address message is we don't have any data content or a pairing authorisation
      SignalServiceProtos.LokiAddressMessage.Builder addressMessage = SignalServiceProtos.LokiAddressMessage.newBuilder().setType(SignalServiceProtos.LokiAddressMessage.Type.HOST_UNREACHABLE);
      container.setLokiAddressMessage(addressMessage);
    }

    return container.build().toByteArray();
  }

  private byte[] createCallContent(SignalServiceCallMessage callMessage) {
    Content.Builder     container = Content.newBuilder();
    CallMessage.Builder builder   = CallMessage.newBuilder();

    if (callMessage.getOfferMessage().isPresent()) {
      OfferMessage offer = callMessage.getOfferMessage().get();
      builder.setOffer(CallMessage.Offer.newBuilder()
                                        .setId(offer.getId())
                                        .setDescription(offer.getDescription()));
    } else if (callMessage.getAnswerMessage().isPresent()) {
      AnswerMessage answer = callMessage.getAnswerMessage().get();
      builder.setAnswer(CallMessage.Answer.newBuilder()
                                          .setId(answer.getId())
                                          .setDescription(answer.getDescription()));
    } else if (callMessage.getIceUpdateMessages().isPresent()) {
      List<IceUpdateMessage> updates = callMessage.getIceUpdateMessages().get();

      for (IceUpdateMessage update : updates) {
        builder.addIceUpdate(CallMessage.IceUpdate.newBuilder()
                                                  .setId(update.getId())
                                                  .setSdp(update.getSdp())
                                                  .setSdpMid(update.getSdpMid())
                                                  .setSdpMLineIndex(update.getSdpMLineIndex()));
      }
    } else if (callMessage.getHangupMessage().isPresent()) {
      builder.setHangup(CallMessage.Hangup.newBuilder().setId(callMessage.getHangupMessage().get().getId()));
    } else if (callMessage.getBusyMessage().isPresent()) {
      builder.setBusy(CallMessage.Busy.newBuilder().setId(callMessage.getBusyMessage().get().getId()));
    }

    container.setCallMessage(builder);
    return container.build().toByteArray();
  }

  private byte[] createMultiDeviceContactsContent(SignalServiceAttachmentStream contacts, boolean complete) throws IOException {
    Content.Builder     container = Content.newBuilder();
    SyncMessage.Builder builder   = createSyncMessageBuilder();
    builder.setContacts(SyncMessage.Contacts.newBuilder()
                                            .setData(ByteString.readFrom(contacts.getInputStream()))
                                            .setComplete(complete));

    return container.setSyncMessage(builder).build().toByteArray();
  }

  private byte[] createMultiDeviceGroupsContent(SignalServiceAttachmentStream groups) throws IOException {
    Content.Builder     container = Content.newBuilder();
    SyncMessage.Builder builder   = createSyncMessageBuilder();
    builder.setGroups(SyncMessage.Groups.newBuilder()
                                        .setBlob(createAttachmentPointer(groups)));

    return container.setSyncMessage(builder).build().toByteArray();
  }

  private byte[] createMultiDeviceSentTranscriptContent(SentTranscriptMessage transcript, Optional<UnidentifiedAccessPair> unidentifiedAccess) throws IOException {
    SignalServiceAddress address = new SignalServiceAddress(transcript.getDestination().get());
    SendMessageResult    result  = SendMessageResult.success(address, unidentifiedAccess.isPresent(), true);

    return createMultiDeviceSentTranscriptContent(createMessageContent(transcript.getMessage(), address),
                                                  Optional.of(address),
                                                  transcript.getTimestamp(),
                                                  Collections.singletonList(result));
  }

  private byte[] createMultiDeviceSentTranscriptContent(byte[] content, Optional<SignalServiceAddress> recipient,
                                                        long timestamp, List<SendMessageResult> sendMessageResults)
  {
    try {
      Content.Builder          container   = Content.newBuilder();
      SyncMessage.Builder      syncMessage = createSyncMessageBuilder();
      SyncMessage.Sent.Builder sentMessage = SyncMessage.Sent.newBuilder();
      DataMessage              dataMessage = Content.parseFrom(content).getDataMessage();

      sentMessage.setTimestamp(timestamp);
      sentMessage.setMessage(dataMessage);

      for (SendMessageResult result : sendMessageResults) {
        if (result.getSuccess() != null) {
          sentMessage.addUnidentifiedStatus(SyncMessage.Sent.UnidentifiedDeliveryStatus.newBuilder()
                                                                                       .setDestination(result.getAddress().getNumber())
                                                                                       .setUnidentified(result.getSuccess().isUnidentified()));
        }
      }

      if (recipient.isPresent()) {
        sentMessage.setDestination(recipient.get().getNumber());
      }

      if (dataMessage.getExpireTimer() > 0) {
        sentMessage.setExpirationStartTimestamp(System.currentTimeMillis());
      }

      return container.setSyncMessage(syncMessage.setSent(sentMessage)).build().toByteArray();
    } catch (InvalidProtocolBufferException e) {
      throw new AssertionError(e);
    }
  }

  private byte[] createMultiDeviceReadContent(List<ReadMessage> readMessages) {
    Content.Builder     container = Content.newBuilder();
    SyncMessage.Builder builder   = createSyncMessageBuilder();

    for (ReadMessage readMessage : readMessages) {
      builder.addRead(SyncMessage.Read.newBuilder()
                                      .setTimestamp(readMessage.getTimestamp())
                                      .setSender(readMessage.getSender()));
    }

    return container.setSyncMessage(builder).build().toByteArray();
  }

  private byte[] createMultiDeviceBlockedContent(BlockedListMessage blocked) {
    Content.Builder             container      = Content.newBuilder();
    SyncMessage.Builder         syncMessage    = createSyncMessageBuilder();
    SyncMessage.Blocked.Builder blockedMessage = SyncMessage.Blocked.newBuilder();

    blockedMessage.addAllNumbers(blocked.getNumbers());

    for (byte[] groupId : blocked.getGroupIds()) {
      blockedMessage.addGroupIds(ByteString.copyFrom(groupId));
    }

    return container.setSyncMessage(syncMessage.setBlocked(blockedMessage)).build().toByteArray();
  }

  private byte[] createMultiDeviceConfigurationContent(ConfigurationMessage configuration) {
    Content.Builder                   container            = Content.newBuilder();
    SyncMessage.Builder               syncMessage          = createSyncMessageBuilder();
    SyncMessage.Configuration.Builder configurationMessage = SyncMessage.Configuration.newBuilder();

    if (configuration.getReadReceipts().isPresent()) {
      configurationMessage.setReadReceipts(configuration.getReadReceipts().get());
    }

    if (configuration.getUnidentifiedDeliveryIndicators().isPresent()) {
      configurationMessage.setUnidentifiedDeliveryIndicators(configuration.getUnidentifiedDeliveryIndicators().get());
    }

    if (configuration.getTypingIndicators().isPresent()) {
      configurationMessage.setTypingIndicators(configuration.getTypingIndicators().get());
    }

    if (configuration.getLinkPreviews().isPresent()) {
      configurationMessage.setLinkPreviews(configuration.getLinkPreviews().get());
    }

    return container.setSyncMessage(syncMessage.setConfiguration(configurationMessage)).build().toByteArray();
  }

  private byte[] createMultiDeviceStickerPackOperationContent(List<StickerPackOperationMessage> stickerPackOperations) {
    Content.Builder     container   = Content.newBuilder();
    SyncMessage.Builder syncMessage = createSyncMessageBuilder();

    for (StickerPackOperationMessage stickerPackOperation : stickerPackOperations) {
      SyncMessage.StickerPackOperation.Builder builder = SyncMessage.StickerPackOperation.newBuilder();

      if (stickerPackOperation.getPackId().isPresent()) {
        builder.setPackId(ByteString.copyFrom(stickerPackOperation.getPackId().get()));
      }

      if (stickerPackOperation.getPackKey().isPresent()) {
        builder.setPackKey(ByteString.copyFrom(stickerPackOperation.getPackKey().get()));
      }

      if (stickerPackOperation.getType().isPresent()) {
        switch (stickerPackOperation.getType().get()) {
          case INSTALL: builder.setType(SyncMessage.StickerPackOperation.Type.INSTALL); break;
          case REMOVE:  builder.setType(SyncMessage.StickerPackOperation.Type.REMOVE); break;
        }
      }

      syncMessage.addStickerPackOperation(builder);
    }

    return container.setSyncMessage(syncMessage).build().toByteArray();
  }

  private byte[] createMultiDeviceVerifiedContent(VerifiedMessage verifiedMessage, byte[] nullMessage) {
    Content.Builder     container              = Content.newBuilder();
    SyncMessage.Builder syncMessage            = createSyncMessageBuilder();
    Verified.Builder    verifiedMessageBuilder = Verified.newBuilder();

    verifiedMessageBuilder.setNullMessage(ByteString.copyFrom(nullMessage));
    verifiedMessageBuilder.setDestination(verifiedMessage.getDestination());
    verifiedMessageBuilder.setIdentityKey(ByteString.copyFrom(verifiedMessage.getIdentityKey().serialize()));

      switch(verifiedMessage.getVerified()) {
        case DEFAULT:    verifiedMessageBuilder.setState(Verified.State.DEFAULT);    break;
        case VERIFIED:   verifiedMessageBuilder.setState(Verified.State.VERIFIED);   break;
        case UNVERIFIED: verifiedMessageBuilder.setState(Verified.State.UNVERIFIED); break;
        default:         throw new AssertionError("Unknown: " + verifiedMessage.getVerified());
      }

    syncMessage.setVerified(verifiedMessageBuilder);
    return container.setSyncMessage(syncMessage).build().toByteArray();
  }

  private SyncMessage.Builder createSyncMessageBuilder() {
    SecureRandom random  = new SecureRandom();
    byte[]       padding = Util.getRandomLengthBytes(512);
    random.nextBytes(padding);

    SyncMessage.Builder builder = SyncMessage.newBuilder();
    builder.setPadding(ByteString.copyFrom(padding));

    return builder;
  }

  private GroupContext createGroupContent(SignalServiceGroup group, SignalServiceAddress recipient) throws IOException {
    GroupContext.Builder builder = GroupContext.newBuilder();
    builder.setId(ByteString.copyFrom(group.getGroupId()));

    if (group.getType() != SignalServiceGroup.Type.DELIVER) {
      if      (group.getType() == SignalServiceGroup.Type.UPDATE)       builder.setType(GroupContext.Type.UPDATE);
      else if (group.getType() == SignalServiceGroup.Type.QUIT)         builder.setType(GroupContext.Type.QUIT);
      else if (group.getType() == SignalServiceGroup.Type.REQUEST_INFO) builder.setType(GroupContext.Type.REQUEST_INFO);
      else                                                              throw new AssertionError("Unknown type: " + group.getType());

      if (group.getName().isPresent()) builder.setName(group.getName().get());
      if (group.getMembers().isPresent()) builder.addAllMembers(group.getMembers().get());

      if (group.getAvatar().isPresent()) {
        if (group.getAvatar().get().isStream()) {
          builder.setAvatar(createAttachmentPointer(group.getAvatar().get().asStream(), recipient));
        } else {
          builder.setAvatar(createAttachmentPointer(group.getAvatar().get().asPointer()));
        }
      }
    } else {
      builder.setType(GroupContext.Type.DELIVER);
    }

    return builder.build();
  }

  private List<DataMessage.Contact> createSharedContactContent(List<SharedContact> contacts, SignalServiceAddress recipient) throws IOException {
    List<DataMessage.Contact> results = new LinkedList<DataMessage.Contact>();

    for (SharedContact contact : contacts) {
      DataMessage.Contact.Name.Builder nameBuilder    = DataMessage.Contact.Name.newBuilder();

      if (contact.getName().getFamily().isPresent())  nameBuilder.setFamilyName(contact.getName().getFamily().get());
      if (contact.getName().getGiven().isPresent())   nameBuilder.setGivenName(contact.getName().getGiven().get());
      if (contact.getName().getMiddle().isPresent())  nameBuilder.setMiddleName(contact.getName().getMiddle().get());
      if (contact.getName().getPrefix().isPresent())  nameBuilder.setPrefix(contact.getName().getPrefix().get());
      if (contact.getName().getSuffix().isPresent())  nameBuilder.setSuffix(contact.getName().getSuffix().get());
      if (contact.getName().getDisplay().isPresent()) nameBuilder.setDisplayName(contact.getName().getDisplay().get());

      DataMessage.Contact.Builder contactBuilder = DataMessage.Contact.newBuilder()
                                                                      .setName(nameBuilder);

      if (contact.getAddress().isPresent()) {
        for (SharedContact.PostalAddress address : contact.getAddress().get()) {
          DataMessage.Contact.PostalAddress.Builder addressBuilder = DataMessage.Contact.PostalAddress.newBuilder();

          switch (address.getType()) {
            case HOME:   addressBuilder.setType(DataMessage.Contact.PostalAddress.Type.HOME); break;
            case WORK:   addressBuilder.setType(DataMessage.Contact.PostalAddress.Type.WORK); break;
            case CUSTOM: addressBuilder.setType(DataMessage.Contact.PostalAddress.Type.CUSTOM); break;
            default:     throw new AssertionError("Unknown type: " + address.getType());
          }

          if (address.getCity().isPresent())         addressBuilder.setCity(address.getCity().get());
          if (address.getCountry().isPresent())      addressBuilder.setCountry(address.getCountry().get());
          if (address.getLabel().isPresent())        addressBuilder.setLabel(address.getLabel().get());
          if (address.getNeighborhood().isPresent()) addressBuilder.setNeighborhood(address.getNeighborhood().get());
          if (address.getPobox().isPresent())        addressBuilder.setPobox(address.getPobox().get());
          if (address.getPostcode().isPresent())     addressBuilder.setPostcode(address.getPostcode().get());
          if (address.getRegion().isPresent())       addressBuilder.setRegion(address.getRegion().get());
          if (address.getStreet().isPresent())       addressBuilder.setStreet(address.getStreet().get());

          contactBuilder.addAddress(addressBuilder);
        }
      }

      if (contact.getEmail().isPresent()) {
        for (SharedContact.Email email : contact.getEmail().get()) {
          DataMessage.Contact.Email.Builder emailBuilder = DataMessage.Contact.Email.newBuilder()
                                                                                    .setValue(email.getValue());

          switch (email.getType()) {
            case HOME:   emailBuilder.setType(DataMessage.Contact.Email.Type.HOME);   break;
            case WORK:   emailBuilder.setType(DataMessage.Contact.Email.Type.WORK);   break;
            case MOBILE: emailBuilder.setType(DataMessage.Contact.Email.Type.MOBILE); break;
            case CUSTOM: emailBuilder.setType(DataMessage.Contact.Email.Type.CUSTOM); break;
            default:     throw new AssertionError("Unknown type: " + email.getType());
          }

          if (email.getLabel().isPresent()) emailBuilder.setLabel(email.getLabel().get());

          contactBuilder.addEmail(emailBuilder);
        }
      }

      if (contact.getPhone().isPresent()) {
        for (SharedContact.Phone phone : contact.getPhone().get()) {
          DataMessage.Contact.Phone.Builder phoneBuilder = DataMessage.Contact.Phone.newBuilder()
                                                                                    .setValue(phone.getValue());

          switch (phone.getType()) {
            case HOME:   phoneBuilder.setType(DataMessage.Contact.Phone.Type.HOME);   break;
            case WORK:   phoneBuilder.setType(DataMessage.Contact.Phone.Type.WORK);   break;
            case MOBILE: phoneBuilder.setType(DataMessage.Contact.Phone.Type.MOBILE); break;
            case CUSTOM: phoneBuilder.setType(DataMessage.Contact.Phone.Type.CUSTOM); break;
            default:     throw new AssertionError("Unknown type: " + phone.getType());
          }

          if (phone.getLabel().isPresent()) phoneBuilder.setLabel(phone.getLabel().get());

          contactBuilder.addNumber(phoneBuilder);
        }
      }

      if (contact.getAvatar().isPresent()) {
        AttachmentPointer pointer = contact.getAvatar().get().getAttachment().isStream() ? createAttachmentPointer(contact.getAvatar().get().getAttachment().asStream(), recipient)
                                                                                         : createAttachmentPointer(contact.getAvatar().get().getAttachment().asPointer());
        contactBuilder.setAvatar(DataMessage.Contact.Avatar.newBuilder()
                                                           .setAvatar(pointer)
                                                           .setIsProfile(contact.getAvatar().get().isProfile()));
      }

      if (contact.getOrganization().isPresent()) {
        contactBuilder.setOrganization(contact.getOrganization().get());
      }

      results.add(contactBuilder.build());
    }

    return results;
  }

  private List<SendMessageResult> sendMessage(long                               messageID,
                                              List<SignalServiceAddress>         recipients,
                                              List<Optional<UnidentifiedAccess>> unidentifiedAccess,
                                              long                               timestamp,
                                              byte[]                             content,
                                              boolean                            online,
                                              int                                ttl)
      throws IOException
  {
    List<SendMessageResult>                results                    = new LinkedList<SendMessageResult>();
    Iterator<SignalServiceAddress>         recipientIterator          = recipients.iterator();
    Iterator<Optional<UnidentifiedAccess>> unidentifiedAccessIterator = unidentifiedAccess.iterator();

    while (recipientIterator.hasNext()) {
      SignalServiceAddress recipient = recipientIterator.next();

      try {
        SendMessageResult result = sendMessage(messageID, recipient, unidentifiedAccessIterator.next(), timestamp, content, online, ttl);
        results.add(result);
      } catch (UntrustedIdentityException e) {
        Log.w(TAG, e);
        results.add(SendMessageResult.identityFailure(recipient, e.getIdentityKey()));
      } catch (UnregisteredUserException e) {
        Log.w(TAG, e);
        results.add(SendMessageResult.unregisteredFailure(recipient));
      } catch (PushNetworkException e) {
        Log.w(TAG, e);
        results.add(SendMessageResult.networkFailure(recipient));
      }
    }

    return results;
  }

  public SendMessageResult lokiSendSyncMessage(long messageID, SignalServiceAddress recipient, long timestamp, byte[] content, int ttl) {
    return sendMessage(messageID, recipient, Optional.<UnidentifiedAccess>absent(), timestamp, content, false, ttl, false);
  }

  private SendMessageResult sendMessage(long                         messageID,
                                        SignalServiceAddress         recipient,
                                        Optional<UnidentifiedAccess> unidentifiedAccess,
                                        long                         timestamp,
                                        byte[]                       content,
                                        boolean                      online,
                                        int                          ttl)
          throws UntrustedIdentityException, IOException {
    return sendMessage(messageID, recipient, unidentifiedAccess, timestamp, content, online, ttl, false);
  }

  private SendMessageResult sendMessage(final long                   messageID,
                                        final SignalServiceAddress   recipient,
                                        Optional<UnidentifiedAccess> unidentifiedAccess,
                                        long                         timestamp,
                                        byte[]                       content,
                                        boolean                      online,
                                        int                          ttl,
                                        boolean                      isFriendRequest)
  {
    long threadID = threadDatabase.getThreadID(recipient.getNumber());
    LokiPublicChat publicChat = threadDatabase.getPublicChat(threadID);
    if (recipient.equals(localAddress)) {
      return SendMessageResult.success(recipient, false, false);
    } else if (publicChat != null) {
      return sendMessageToPublicChat(messageID, recipient, timestamp, content, publicChat);
    } else {
      return sendMessageToPrivateChat(messageID, recipient, unidentifiedAccess, timestamp, content, online, ttl, isFriendRequest);
    }
  }

  private SendMessageResult sendMessageToPublicChat(final long                   messageID,
                                                    final SignalServiceAddress   recipient,
                                                    long                         timestamp,
                                                    byte[]                       content,
                                                    LokiPublicChat               publicChat) {
    final SettableFuture<?>[] future = {new SettableFuture<Unit>()};
    String displayName = userDatabase.getDisplayName(userHexEncodedPublicKey);
    if (displayName == null) displayName = "Anonymous";
    try {
      SignalServiceProtos.DataMessage data = SignalServiceProtos.Content.parseFrom(content).getDataMessage();
      String body = (data.getBody() != null && data.getBody().length() > 0) ? data.getBody() : Long.toString(data.getTimestamp());
      LokiPublicChatMessage.Quote quote = null;
      if (data.hasQuote()) {
        long quoteID = data.getQuote().getId();
        String quoteeHexEncodedPublicKey = data.getQuote().getAuthor();
        long serverID = messageDatabase.getQuoteServerID(quoteID, quoteeHexEncodedPublicKey);
        quote = new LokiPublicChatMessage.Quote(quoteID, quoteeHexEncodedPublicKey, data.getQuote().getText(), serverID);
      }
      SignalServiceProtos.DataMessage.Preview linkPreview = (data.getPreviewList().size() > 0) ? data.getPreviewList().get(0) : null;
      ArrayList<LokiPublicChatMessage.Attachment> attachments = new ArrayList<>();
      if (linkPreview != null && linkPreview.hasImage()) {
        AttachmentPointer attachmentPointer = linkPreview.getImage();
        String caption = attachmentPointer.hasCaption() ? attachmentPointer.getCaption() : null;
        attachments.add(new LokiPublicChatMessage.Attachment(
                LokiPublicChatMessage.Attachment.Kind.LinkPreview,
                publicChat.getServer(),
                attachmentPointer.getId(),
                attachmentPointer.getContentType(),
                attachmentPointer.getSize(),
                attachmentPointer.getFileName(),
                attachmentPointer.getFlags(),
                attachmentPointer.getWidth(),
                attachmentPointer.getHeight(),
                caption,
                attachmentPointer.getUrl(),
                linkPreview.getUrl(),
                linkPreview.getTitle()
        ));
      }
      for (AttachmentPointer attachmentPointer : data.getAttachmentsList()) {
        String caption = attachmentPointer.hasCaption() ? attachmentPointer.getCaption() : null;
        attachments.add(new LokiPublicChatMessage.Attachment(
                LokiPublicChatMessage.Attachment.Kind.Attachment,
                publicChat.getServer(),
                attachmentPointer.getId(),
                attachmentPointer.getContentType(),
                attachmentPointer.getSize(),
                attachmentPointer.getFileName(),
                attachmentPointer.getFlags(),
                attachmentPointer.getWidth(),
                attachmentPointer.getHeight(),
                caption,
                attachmentPointer.getUrl(),
                null,
                null
        ));
      }
      LokiPublicChatMessage message = new LokiPublicChatMessage(userHexEncodedPublicKey, displayName, body, timestamp, LokiPublicChatAPI.getPublicChatMessageType(), quote, attachments);
      byte[] privateKey = store.getIdentityKeyPair().getPrivateKey().serialize();
      new LokiPublicChatAPI(userHexEncodedPublicKey, privateKey, apiDatabase, userDatabase).sendMessage(message, publicChat.getChannel(), publicChat.getServer()).success(new Function1<LokiPublicChatMessage, Unit>() {

        @Override
        public Unit invoke(LokiPublicChatMessage message) {
          @SuppressWarnings("unchecked") SettableFuture<Unit> f = (SettableFuture<Unit>)future[0];
          messageDatabase.setServerID(messageID, message.getServerID());
          f.set(Unit.INSTANCE);
          return Unit.INSTANCE;
        }
      }).fail(new Function1<Exception, Unit>() {

        @Override
        public Unit invoke(Exception exception) {
          @SuppressWarnings("unchecked") SettableFuture<Unit> f = (SettableFuture<Unit>)future[0];
          f.setException(exception);
          return Unit.INSTANCE;
        }
      });
    } catch (Exception exception) {
      @SuppressWarnings("unchecked") SettableFuture<Unit> f = (SettableFuture<Unit>)future[0];
      f.setException(exception);
    }

    @SuppressWarnings("unchecked") SettableFuture<Unit> f = (SettableFuture<Unit>)future[0];
    try {
      f.get(1, TimeUnit.MINUTES);
      return SendMessageResult.success(recipient, false, false);
    } catch (Exception exception) {
      return SendMessageResult.networkFailure(recipient);
    }
  }

  private SendMessageResult sendMessageToPrivateChat(final long                   messageID,
                                                     final SignalServiceAddress   recipient,
                                                     Optional<UnidentifiedAccess> unidentifiedAccess,
                                                     long                         timestamp,
                                                     byte[]                       content,
                                                     boolean                      online,
                                                     int                          ttl,
                                                     boolean                      isFriendRequest)
  {
    final SettableFuture<?>[] future = {new SettableFuture<Unit>()};
    final long threadID = threadDatabase.getThreadID(recipient.getNumber());
    try {
      OutgoingPushMessageList messages = getEncryptedMessages(socket, recipient, unidentifiedAccess, timestamp, content, online, isFriendRequest);
      OutgoingPushMessage message = messages.getMessages().get(0);
      final SignalServiceProtos.Envelope.Type type = SignalServiceProtos.Envelope.Type.valueOf(message.type);
      final boolean isFriendRequestMessage = type == SignalServiceProtos.Envelope.Type.FRIEND_REQUEST;
      // Make sure we have a valid ttl; otherwise default to a day
      if (ttl <= 0) { ttl = 24 * 60 * 60 * 1000; }
      SignalMessageInfo messageInfo = new SignalMessageInfo(type, timestamp, userHexEncodedPublicKey, SignalServiceAddress.DEFAULT_DEVICE_ID, message.content, recipient.getNumber(), ttl, false);
      // TODO: PoW indicator
      // Update the message and thread if needed
      if (isFriendRequestMessage && eventListener.isPresent()) { eventListener.get().onFriendRequestSending(messageID, threadID); }
      LokiAPI api = new LokiAPI(userHexEncodedPublicKey, apiDatabase);
      api.sendSignalMessage(messageInfo, new Function0<Unit>() {

        @Override
        public Unit invoke() {
          // TODO: onP2PSuccess
          return Unit.INSTANCE;
        }
      }).success(new Function1<Set<Promise<Map<?, ?>, Exception>>, Unit>() {

        @Override
        public Unit invoke(Set<Promise<Map<?, ?>, Exception>> promises) {
          final boolean[] isSuccess = {false};
          final int[] promiseCount = {promises.size()};
          final int[] errorCount = {0};
          for (Promise<Map<?, ?>, Exception> promise : promises) {
            promise.success(new Function1<Map<?, ?>, Unit>() {

              @Override
              public Unit invoke(Map<?, ?> map) {
                if (isSuccess[0]) { return Unit.INSTANCE; } // Succeed as soon as the first promise succeeds
                Analytics.Companion.getShared().track("Sent Message Using Swarm API");
                isSuccess[0] = true;
                // Update the message and thread if needed
                if (isFriendRequestMessage && eventListener.isPresent()) {
                    eventListener.get().onFriendRequestSent(messageID, threadID);
                }
                @SuppressWarnings("unchecked") SettableFuture<Unit> f = (SettableFuture<Unit>)future[0];
                f.set(Unit.INSTANCE);
                return Unit.INSTANCE;
              }
            }).fail(new Function1<Exception, Unit>() {

              @Override
              public Unit invoke(Exception exception) {
                errorCount[0] += 1;
                if (errorCount[0] != promiseCount[0]) { return Unit.INSTANCE; } // Only error out if all promises failed
                Analytics.Companion.getShared().track("Failed to Send Message Using Swarm API");
                // Update the message and thread if needed
                if (isFriendRequestMessage && eventListener.isPresent()) {
                    eventListener.get().onFriendRequestSendingFail(messageID, threadID);
                }
                @SuppressWarnings("unchecked") SettableFuture<Unit> f = (SettableFuture<Unit>)future[0];
                f.setException(exception);
                return Unit.INSTANCE;
              }
            });
          }
          return Unit.INSTANCE;
        }
      }).fail(new Function1<Exception, Unit>() {

        @Override
        public Unit invoke(Exception exception) { // The snode is unreachable
          // Update the message and thread if needed
          if (isFriendRequestMessage && eventListener.isPresent()) {
              eventListener.get().onFriendRequestSendingFail(messageID, threadID);
          }
          @SuppressWarnings("unchecked") SettableFuture<Unit> f = (SettableFuture<Unit>)future[0];
          f.setException(exception);
          return Unit.INSTANCE;
        }
      });
    } catch (Exception exception) {
      @SuppressWarnings("unchecked") SettableFuture<Unit> f = (SettableFuture<Unit>)future[0];
      f.setException(exception);
    }
    @SuppressWarnings("unchecked") SettableFuture<Unit> f = (SettableFuture<Unit>)future[0];
    try {
      f.get(1, TimeUnit.MINUTES);
      return SendMessageResult.success(recipient, false, false);
    } catch (Exception exception) {
      return SendMessageResult.networkFailure(recipient);
    }
  }

  private List<AttachmentPointer> createAttachmentPointers(Optional<List<SignalServiceAttachment>> attachments, SignalServiceAddress recipient) throws IOException {
    List<AttachmentPointer> pointers = new LinkedList<AttachmentPointer>();

    if (!attachments.isPresent() || attachments.get().isEmpty()) {
      Log.w(TAG, "No attachments present...");
      return pointers;
    }

    for (SignalServiceAttachment attachment : attachments.get()) {
      if (attachment.isStream()) {
        Log.w(TAG, "Found attachment, creating pointer...");
        pointers.add(createAttachmentPointer(attachment.asStream(), recipient));
      } else if (attachment.isPointer()) {
        Log.w(TAG, "Including existing attachment pointer...");
        pointers.add(createAttachmentPointer(attachment.asPointer()));
      }
    }

    return pointers;
  }

  private AttachmentPointer createAttachmentPointer(SignalServiceAttachmentPointer attachment) {
    AttachmentPointer.Builder builder = AttachmentPointer.newBuilder()
                                                         .setContentType(attachment.getContentType())
                                                         .setId(attachment.getId())
                                                         .setKey(ByteString.copyFrom(attachment.getKey()))
                                                         .setDigest(ByteString.copyFrom(attachment.getDigest().get()))
                                                         .setSize(attachment.getSize().get())
                                                         .setUrl(attachment.getUrl());

    if (attachment.getFileName().isPresent()) {
      builder.setFileName(attachment.getFileName().get());
    }

    if (attachment.getPreview().isPresent()) {
      builder.setThumbnail(ByteString.copyFrom(attachment.getPreview().get()));
    }

    if (attachment.getWidth() > 0) {
      builder.setWidth(attachment.getWidth());
    }

    if (attachment.getHeight() > 0) {
      builder.setHeight(attachment.getHeight());
    }

    if (attachment.getVoiceNote()) {
      builder.setFlags(AttachmentPointer.Flags.VOICE_MESSAGE_VALUE);
    }

    if (attachment.getCaption().isPresent()) {
      builder.setCaption(attachment.getCaption().get());
    }

    return builder.build();
  }

  private AttachmentPointer createAttachmentPointer(SignalServiceAttachmentStream attachment)
          throws IOException
  {
    return createAttachmentPointer(attachment, false, null);
  }

  private AttachmentPointer createAttachmentPointer(SignalServiceAttachmentStream attachment, SignalServiceAddress recipient)
    throws IOException
  {
    return createAttachmentPointer(attachment, false, recipient);
  }

  private AttachmentPointer createAttachmentPointer(SignalServiceAttachmentStream attachment, boolean usePadding, SignalServiceAddress recipient)
      throws IOException
  {
    SignalServiceAttachmentPointer pointer = uploadAttachment(attachment, usePadding, recipient);
    return createAttachmentPointer(pointer);
  }


  private OutgoingPushMessageList getEncryptedMessages(PushServiceSocket            socket,
                                                       SignalServiceAddress         recipient,
                                                       Optional<UnidentifiedAccess> unidentifiedAccess,
                                                       long                         timestamp,
                                                       byte[]                       plaintext,
                                                       boolean                      online,
                                                       boolean                      isFriendRequest)
      throws IOException, InvalidKeyException, UntrustedIdentityException
  {
    List<OutgoingPushMessage> messages = new LinkedList<OutgoingPushMessage>();

    if (!recipient.equals(localAddress) || unidentifiedAccess.isPresent()) {
      if (isFriendRequest) {
        messages.add(getEncryptedFriendRequestMessage(recipient, SignalServiceAddress.DEFAULT_DEVICE_ID, plaintext));
      } else {
        messages.add(getEncryptedMessage(socket, recipient, unidentifiedAccess, SignalServiceAddress.DEFAULT_DEVICE_ID, plaintext));
      }
    }

    /* Loki - Disable this as we don't support multi-device sending yet
    for (int deviceId : store.getSubDeviceSessions(recipient.getNumber())) {
      if (store.containsSession(new SignalProtocolAddress(recipient.getNumber(), deviceId))) {
        messages.add(getEncryptedMessage(socket, recipient, unidentifiedAccess, deviceId, plaintext));
      }
    }
     */

    return new OutgoingPushMessageList(recipient.getNumber(), timestamp, messages, online);
  }

  private OutgoingPushMessage getEncryptedMessage(PushServiceSocket            socket,
                                                  SignalServiceAddress         recipient,
                                                  Optional<UnidentifiedAccess> unidentifiedAccess,
                                                  int                          deviceId,
                                                  byte[]                       plaintext)
      throws IOException, InvalidKeyException, UntrustedIdentityException
  {
    SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(recipient.getNumber(), deviceId);
    SignalServiceCipher cipher = new SignalServiceCipher(localAddress, store, null);

    // Loki - Use custom pre key bundle handling
    if (!store.containsSession(signalProtocolAddress)) {
      try {
        String contactHexEncodedPublicKey = recipient.getNumber();
        PreKeyBundle preKeyBundle = preKeyBundleDatabase.getPreKeyBundle(contactHexEncodedPublicKey);
        if (preKeyBundle == null) {
          throw new InvalidKeyException("Pre key bundle not found for: " + recipient.getNumber() + ".");
        }
        try {
          SignalProtocolAddress address = new SignalProtocolAddress(contactHexEncodedPublicKey, preKeyBundle.getDeviceId());
          SessionBuilder sessionBuilder = new SessionBuilder(store, address);
          sessionBuilder.process(preKeyBundle);
          // Loki - Discard the pre key bundle once the session has been initiated
          preKeyBundleDatabase.removePreKeyBundle(contactHexEncodedPublicKey);
        } catch (org.whispersystems.libsignal.UntrustedIdentityException e) {
          throw new UntrustedIdentityException("Untrusted identity key", recipient.getNumber(), preKeyBundle.getIdentityKey());
        }
        if (eventListener.isPresent()) {
          eventListener.get().onSecurityEvent(recipient);
        }
      } catch (InvalidKeyException e) {
        throw new IOException(e);
      }
    }

    // Ensure all session building processing has been done
    synchronized (SessionCipher.SESSION_LOCK) {
      try {
        return cipher.encrypt(signalProtocolAddress, unidentifiedAccess, plaintext);
      } catch (org.whispersystems.libsignal.UntrustedIdentityException e) {
        throw new UntrustedIdentityException("Untrusted on send", recipient.getNumber(), e.getUntrustedIdentity());
      }
    }
  }

  private OutgoingPushMessage getEncryptedFriendRequestMessage(SignalServiceAddress recipient, int deviceID, byte[] plaintext) {
      SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(recipient.getNumber(), deviceID);
      LokiServiceCipher cipher = new LokiServiceCipher(localAddress, store, null, null, null);
      return cipher.encryptFriendRequest(signalProtocolAddress, plaintext);
  }

  private void handleMismatchedDevices(PushServiceSocket socket, SignalServiceAddress recipient,
                                       MismatchedDevices mismatchedDevices)
      throws IOException, UntrustedIdentityException
  {
    try {
      for (int extraDeviceId : mismatchedDevices.getExtraDevices()) {
        store.deleteSession(new SignalProtocolAddress(recipient.getNumber(), extraDeviceId));
      }

      for (int missingDeviceId : mismatchedDevices.getMissingDevices()) {
        PreKeyBundle preKey = socket.getPreKey(recipient, missingDeviceId);

        try {
          SessionBuilder sessionBuilder = new SessionBuilder(store, new SignalProtocolAddress(recipient.getNumber(), missingDeviceId));
          sessionBuilder.process(preKey);
        } catch (org.whispersystems.libsignal.UntrustedIdentityException e) {
          throw new UntrustedIdentityException("Untrusted identity key!", recipient.getNumber(), preKey.getIdentityKey());
        }
      }
    } catch (InvalidKeyException e) {
      throw new IOException(e);
    }
  }

  private void handleStaleDevices(SignalServiceAddress recipient, StaleDevices staleDevices) {
    for (int staleDeviceId : staleDevices.getStaleDevices()) {
      store.deleteSession(new SignalProtocolAddress(recipient.getNumber(), staleDeviceId));
    }
  }

  private Optional<UnidentifiedAccess> getTargetUnidentifiedAccess(Optional<UnidentifiedAccessPair> unidentifiedAccess) {
    if (unidentifiedAccess.isPresent()) {
      return unidentifiedAccess.get().getTargetUnidentifiedAccess();
    }

    return Optional.absent();
  }

  private List<Optional<UnidentifiedAccess>> getTargetUnidentifiedAccess(List<Optional<UnidentifiedAccessPair>> unidentifiedAccess) {
    List<Optional<UnidentifiedAccess>> results = new LinkedList<Optional<UnidentifiedAccess>>();

    for (Optional<UnidentifiedAccessPair> item : unidentifiedAccess) {
      if (item.isPresent()) results.add(item.get().getTargetUnidentifiedAccess());
      else                  results.add(Optional.<UnidentifiedAccess>absent());
    }

    return results;
  }

  public static interface EventListener {
    public void onSecurityEvent(SignalServiceAddress address);
    public void onSyncEvent(long messageID, long timestamp, byte[] message, int ttl);
    public void onFriendRequestSending(long messageID, long threadID);
    public void onFriendRequestSent(long messageID, long threadID);
    public void onFriendRequestSendingFail(long messageID, long threadID);
  }

}
