package com.avos.avoscloud;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;
import com.avos.avoscloud.AVIMOperationQueue.Operation;
import com.avos.avoscloud.PendingMessageCache.Message;
import com.avos.avoscloud.SignatureFactory.SignatureException;
import com.avos.avoscloud.im.v2.AVIMException;
import com.avos.avoscloud.im.v2.AVIMMessage;
import com.avos.avoscloud.im.v2.AVIMTypedMessage;
import com.avos.avoscloud.im.v2.Conversation;
import com.avos.avoscloud.im.v2.Conversation.AVIMOperation;
import com.avos.avospush.push.AVWebSocketListener;
import com.avos.avospush.session.CommandPacket;
import com.avos.avospush.session.ConversationAckPacket;
import com.avos.avospush.session.ConversationControlPacket.ConversationControlOp;
import com.avos.avospush.session.MessageReceiptCache;
import com.avos.avospush.session.SessionAckPacket;
import com.avos.avospush.session.SessionControlPacket;
import com.avos.avospush.session.StaleMessageDepot;

class AVSessionWebSocketListener implements AVWebSocketListener {

  AVSession session;
  private final StaleMessageDepot depot;
  private static final String SESSION_MESSASGE_DEPOT = "com.avos.push.session.message.";

  public AVSessionWebSocketListener(AVSession session) {
    this.session = session;
    depot = new StaleMessageDepot(SESSION_MESSASGE_DEPOT + session.getSelfPeerId());
  }

  private static final int CODE_SESSION_SIGNATURE_FAILURE = 4102;
  private static final int CODE_SESSION_TOKEN_FAILURE = 4112;

  @Override
  public void onWebSocketOpen() {
    if (session.sessionOpened.get() || session.sessionResume.get()) {
      if (AVOSCloud.showInternalDebugLog()) {
        LogUtil.avlog.d("web socket opened, send session open.");
      }

      String token = AVSessionCacheHelper.IMSessionTokenCache.getIMSessionToken(session.getSelfPeerId());
      if (!AVUtils.isBlankString(token)) {
        openWithSessionToken(token);
      } else {
        openWithSignature();
      }
    }
  }

  /**
   * 使用im-sessionToken来登录
   */
  private void openWithSessionToken(String token) {
    SessionControlPacket scp = SessionControlPacket.genSessionCommand(
      session.getSelfPeerId(), null, SessionControlPacket.SessionControlOp.OPEN, null, session.getLastNotifyTime(), session.getLastPatchTime(), null);
    scp.setSessionToken(token);
    scp.setReconnectionRequest(true);
    PushService.sendData(scp);
  }

  /**
   * 使用签名登陆
   */
  private void openWithSignature() {
    final SignatureCallback callback = new SignatureCallback() {

      @Override
      public void onSignatureReady(Signature sig, AVException exception) {
        int requestId = AVUtils.getNextIMRequestId();
        SessionControlPacket scp = SessionControlPacket.genSessionCommand(
          session.getSelfPeerId(), null,
          SessionControlPacket.SessionControlOp.OPEN, sig, session.getLastNotifyTime(), session.getLastPatchTime(), null);
        scp.setTag(session.tag);
        scp.setReconnectionRequest(true);
        if (session.conversationOperationCache != null) {
          session.conversationOperationCache.offer(Operation.getOperation(
            AVIMOperation.CLIENT_OPEN.getCode(), session.getSelfPeerId(), null, requestId));
        }
        PushService.sendData(scp);
      }

      @Override
      public boolean cacheSignature() {
        // 自动登录是因为会使用 im-sessionToken 来登录，所以缓存是没有必要的
        return false;
      }

      @Override
      public boolean useSignatureCache() {
        return false;
      }

      @Override
      public Signature computeSignature() throws SignatureException {
        final SignatureFactory signatureFactory = AVSession.getSignatureFactory();
        if (null != signatureFactory) {
          return signatureFactory.createSignature(session.getSelfPeerId(), new ArrayList<String>());
        } else if (!AVUtils.isBlankString(session.getSessionToken())) {
          try {
            return new AVUserSinatureFactory(session.getSessionToken()).getOpenSignature();
          } catch (AVException e) {
            throw  new SignatureException(e.getCode(), e.getMessage());
          }
        }
        return null;
      }
    };
    // 在某些特定的rom上，socket回来的线程会与Service本身的线程不一致，导致AsyncTask调用出现异常
    if (!AVUtils.isMainThread()) {
      AVOSCloud.handler.post(new Runnable() {
        @Override
        public void run() {
          new SignatureTask(callback).execute(session.getSelfPeerId());
        }
      });
    } else {
      new SignatureTask(callback).execute(session.getSelfPeerId());
    }
  }

  @Override
  public void onWebSocketClose() {
    if (!session.sessionPaused.getAndSet(true)) {
      try {
        session.sessionListener.onSessionPaused(AVOSCloud.applicationContext, session);
        // 这里给所有的消息发送失败消息
        if (session.pendingMessages != null && !session.pendingMessages.isEmpty()) {
          while (!session.pendingMessages.isEmpty()) {
            Message m = session.pendingMessages.poll();
            if (!AVUtils.isBlankString(m.cid)) {
              AVInternalConversation conversation = session.getConversation(m.cid);
              BroadcastUtil.sendIMLocalBroadcast(session.getSelfPeerId(),
                  conversation.conversationId, Integer.parseInt(m.id), new RuntimeException(
                      "Connection Lost"),
                  AVIMOperation.CONVERSATION_SEND_MESSAGE);
            }
          }
        }
        if (session.conversationOperationCache != null
            && !session.conversationOperationCache.isEmpty()) {
          for (int i = 0; i < session.conversationOperationCache.cache.size(); i++) {
            int requestId = session.conversationOperationCache.cache.keyAt(i);
            Operation op = session.conversationOperationCache.poll(requestId);
            BroadcastUtil.sendIMLocalBroadcast(op.sessionId, op.conversationId, requestId,
                new IllegalStateException("Connection Lost"),
                AVIMOperation.getAVIMOperation(op.operation));
          }
        }
      } catch (Exception e) {
        session.sessionListener.onError(AVOSCloud.applicationContext, session, e);
      }
    }
  }

  @Override
  public void onDirectCommand(Messages.DirectCommand directCommand) {
    final String msg = directCommand.getMsg();
    final String fromPeerId = directCommand.getFromPeerId();
    final String conversationId = directCommand.getCid();
    final Long timestamp = directCommand.getTimestamp();
    final String messageId = directCommand.getId();
    final boolean isTransient = directCommand.hasTransient() && directCommand.getTransient();
    final boolean hasMore = directCommand.hasHasMore() && directCommand.getHasMore();
    final long patchTimestamp = directCommand.getPatchTimestamp();

    try {
      if (!isTransient) {
        if (!AVUtils.isBlankString(conversationId)) {
          PushService.sendData(ConversationAckPacket.getConversationAckPacket(
            session.getSelfPeerId(), conversationId, messageId));
        } else {
          PushService.sendData(genSessionAckPacket(messageId));
        }
      }

      if (depot.putStableMessage(messageId) && !AVUtils.isBlankString(conversationId)) {
        AVInternalConversation conversation = session.getConversation(conversationId);
        AVIMMessage message = new AVIMMessage(conversationId, fromPeerId, timestamp, -1);
        message.setMessageId(messageId);
        message.setContent(msg);
        message.setUpdateAt(patchTimestamp);
        conversation.onMessage(message, hasMore, isTransient);
      }
    } catch (Exception e) {
      session.sessionListener.onError(AVOSCloud.applicationContext, session, e);
    }
  }

  @Override
  public void onSessionCommand(String op, Integer requestKey, Messages.SessionCommand command) {
    if (op.equals(SessionControlPacket.SessionControlOp.OPENED)) {
      try {
        session.sessionOpened.set(true);
        session.sessionResume.set(false);

        if (!session.sessionPaused.getAndSet(false)) {
          int requestId = (null != requestKey ? requestKey : CommandPacket.UNSUPPORTED_OPERATION);
          if (requestId != CommandPacket.UNSUPPORTED_OPERATION) {
            session.conversationOperationCache.poll(requestId);
          }
          session.sessionListener.onSessionOpen(AVOSCloud.applicationContext, session, requestId);
          if (command.hasSt() && command.hasStTtl()) {
            AVSessionCacheHelper.IMSessionTokenCache.addIMSessionToken(session.getSelfPeerId(),
              command.getSt(), Integer.valueOf(command.getStTtl()));
          }
        } else {
          if (AVOSCloud.showInternalDebugLog()) {
            LogUtil.avlog.d("session resumed");
          }
          session.sessionListener.onSessionResumed(AVOSCloud.applicationContext, session);
        }
      } catch (Exception e) {
        session.sessionListener.onError(AVOSCloud.applicationContext, session, e);
      }
    } else if (op.equals(SessionControlPacket.SessionControlOp.QUERY_RESULT)) {
      final List<String> sessionPeerIds = command.getOnlineSessionPeerIdsList();
      int requestId = (null != requestKey ? requestKey : CommandPacket.UNSUPPORTED_OPERATION);
      session.sessionListener.onOnlineQuery(AVOSCloud.applicationContext, session, sessionPeerIds,
          requestId);

    } else if (op.equals(SessionControlPacket.SessionControlOp.CLOSED)) {
      int requestId = (null != requestKey ? requestKey : CommandPacket.UNSUPPORTED_OPERATION);
      if (command.hasCode()) {
        session.sessionListener.onSessionClosedFromServer(AVOSCloud.applicationContext, session,
            command.getCode());
      } else {
        if (requestId != CommandPacket.UNSUPPORTED_OPERATION) {
          session.conversationOperationCache.poll(requestId);
        }
        session.sessionListener.onSessionClose(AVOSCloud.applicationContext, session, requestId);
      }
    }
  }

  private void onAckError(Integer requestKey, Messages.AckCommand command, Message m) {
    Operation op = session.conversationOperationCache.poll(requestKey);
    if (op.operation == AVIMOperation.CLIENT_OPEN.getCode()) {
      session.sessionOpened.set(false);
      session.sessionResume.set(false);
    }
    AVIMOperation operation = AVIMOperation.getAVIMOperation(op.operation);
    int code = command.getCode();
    int appCode = (command.hasAppCode() ? command.getAppCode() : 0);
    String reason = command.getReason();
    AVException error = new AVIMException(code, appCode, reason);
    BroadcastUtil.sendIMLocalBroadcast(session.getSelfPeerId(), op.conversationId,
      requestKey, error, operation);
  }

  @Override
  public void onAckCommand(Integer requestKey, Messages.AckCommand ackCommand) {
    session.setServerAckReceived(System.currentTimeMillis() / 1000);
    long timestamp = ackCommand.getT();
    final Message m = session.pendingMessages.poll(String.valueOf(requestKey));
    if (ackCommand.hasCode()) {
      // 这里是发送消息异常时的ack
      this.onAckError(requestKey, ackCommand, m);
    } else {
      if (!AVUtils.isBlankString(m.cid)) {
        AVInternalConversation conversation = session.getConversation(m.cid);
        session.conversationOperationCache.poll(requestKey);
        String msgId = ackCommand.getUid();
        conversation.onMessageSent(requestKey, msgId, timestamp);
        if (m.requestReceipt) {
          m.timestamp = timestamp;
          m.id = msgId;
          MessageReceiptCache.add(session.getSelfPeerId(), msgId, m);
        }
      }
    }
  }

  @Override
  public void onListenerAdded(boolean open) {
    if (open) {
      if (AVOSCloud.showInternalDebugLog()) {
        LogUtil.avlog.d("web socket opened, send session open.");
      }
      this.onWebSocketOpen();
    }
  }

  @Override
  public void onListenerRemoved() {

  }

  @Override
  public void onMessageReceipt(Messages.RcpCommand rcpCommand) {
    try {
      if (rcpCommand.hasT()) {
        final Long timestamp = rcpCommand.getT();
        final String conversationId = rcpCommand.getCid();
        if (!AVUtils.isBlankString(conversationId)) {
          processConversationDeliveredAt(conversationId, timestamp);
          processMessageReceipt(rcpCommand.getId(), conversationId, timestamp);
        }
      }
    } catch (Exception e) {
      session.sessionListener.onError(AVOSCloud.applicationContext, session, e);
    }
  }

  /**
   * 处理 v2 版本中 conversation 的 deliveredAt 事件
   * @param conversationId
   * @param timestamp
   */
  private void processConversationDeliveredAt(String conversationId, long timestamp) {
    AVInternalConversation conversation = session.getConversation(conversationId);
    conversation.onConversationDeliveredAtEvent(timestamp);
  }

  /**
   * 处理 v2 版本中 message 的 rcp 消息
   * @param msgId
   * @param conversationId
   * @param timestamp
   */
  private void processMessageReceipt(String msgId, String conversationId, long timestamp) {
    Object messageCache =
      MessageReceiptCache.get(session.getSelfPeerId(), msgId);
    if (messageCache == null) {
      return;
    }
    Message m = (Message) messageCache;
    AVIMMessage msg =
      new AVIMMessage(conversationId, session.getSelfPeerId(), m.timestamp, timestamp);
    msg.setMessageId(m.id);
    msg.setContent(m.msg);
    msg.setMessageStatus(AVIMMessage.AVIMMessageStatus.AVIMMessageStatusReceipt);
    AVInternalConversation conversation = session.getConversation(conversationId);
    conversation.onMessageReceipt(msg);
  }

  @Override
  public void onReadCmdReceipt(Messages.RcpCommand rcpCommand) {
    if (rcpCommand.hasRead() && rcpCommand.hasCid()) {
      final Long timestamp = rcpCommand.getT();
      String conversationId = rcpCommand.getCid();
      AVInternalConversation conversation = session.getConversation(conversationId);
      conversation.onConversationReadAtEvent(timestamp);
    }
  }

  @Override
  public void onConversationCommand(String operation, Integer requestKey, Messages.ConvCommand convCommand) {
    if (ConversationControlOp.QUERY_RESULT.equals(operation)) {
      Operation op = session.conversationOperationCache.poll(requestKey);
      if (op.operation == AVIMOperation.CONVERSATION_QUERY.getCode()) {
        String result = convCommand.getResults().getData();
        Bundle bundle = new Bundle();
        bundle.putString(Conversation.callbackData, result);
        BroadcastUtil.sendIMLocalBroadcast(session.getSelfPeerId(), null, requestKey, bundle, AVIMOperation.CONVERSATION_QUERY);
      }
    } else {
      String conversationId = null;
      int requestId = (null != requestKey ? requestKey : CommandPacket.UNSUPPORTED_OPERATION);
      AVIMOperation originOperation = null;
      if ((operation.equals(ConversationControlOp.ADDED)
          || operation.equals(ConversationControlOp.REMOVED)
          || operation.equals(ConversationControlOp.UPDATED)
          || operation.equals(ConversationControlOp.MEMBER_COUNT_QUERY_RESULT))
          && requestId != CommandPacket.UNSUPPORTED_OPERATION) {

        Operation op = session.conversationOperationCache.poll(requestId);
        originOperation = AVIMOperation.getAVIMOperation(op.operation);
        conversationId = op.conversationId;
      } else {
        if (operation.equals(ConversationControlOp.STARTED)) {
          session.conversationOperationCache.poll(requestId);
        }
        conversationId = convCommand.getCid();
      }
      if (!AVUtils.isBlankString(conversationId)) {
        AVInternalConversation conversation = session.getConversation(conversationId);
        conversation.processConversationCommandFromServer(originOperation, operation, requestId, convCommand);
      }
    }
  }

  private SessionAckPacket genSessionAckPacket(String messageId) {
    SessionAckPacket sap = new SessionAckPacket();
    sap.setPeerId(session.getSelfPeerId());
    if (!AVUtils.isBlankString(messageId)) {
      sap.setMessageId(messageId);
    }

    return sap;
  }

  @Override
  public void onError(Integer requestKey, Messages.ErrorCommand errorCommand) {
    if (null != requestKey && requestKey != CommandPacket.UNSUPPORTED_OPERATION) {
      Operation op = session.conversationOperationCache.poll(requestKey);
      if (op.operation == AVIMOperation.CLIENT_OPEN.getCode()) {
        session.sessionOpened.set(false);
        session.sessionResume.set(false);
      }
      int code = errorCommand.getCode();
      int appCode = (errorCommand.hasAppCode() ? errorCommand.getAppCode() : 0);
      String reason = errorCommand.getReason();
      AVIMOperation operation = AVIMOperation.getAVIMOperation(op.operation);
      BroadcastUtil.sendIMLocalBroadcast(session.getSelfPeerId(), null, requestKey,
          new AVIMException(code, appCode, reason), operation);
    }

    // 如果遇到signature failure的异常,清除缓存
    if (null == requestKey) {
      int code = errorCommand.getCode();
      // 如果遇到signature failure的异常,清除缓存
      if (CODE_SESSION_SIGNATURE_FAILURE == code) {
        AVSessionCacheHelper.getTagCacheInstance().removeSession(session.getSelfPeerId());
      } else if (CODE_SESSION_TOKEN_FAILURE == code) {
        // 如果遇到session token 失效或者过期的情况，先是清理缓存，然后再重新触发一次自动登录
        AVSessionCacheHelper.IMSessionTokenCache.removeIMSessionToken(session.getSelfPeerId());
        this.onWebSocketOpen();
      }
    }
  }

  @Override
  public void onHistoryMessageQuery(Integer requestKey, Messages.LogsCommand command) {
    if (null != requestKey && requestKey != CommandPacket.UNSUPPORTED_OPERATION) {
      Operation op = session.conversationOperationCache.poll(requestKey);
      AVInternalConversation conversation = session.getConversation(op.conversationId);
      conversation.processMessages(requestKey, command.getLogsList());
    }
  }

  @Override
  public void onUnreadMessagesCommand(Messages.UnreadCommand unreadCommand) {
    session.updateLastNotifyTime(unreadCommand.getNotifTime());
    if (unreadCommand.getConvsCount() > 0) {
      List<Messages.UnreadTuple> unreadTupleList = unreadCommand.getConvsList();
      if (null != unreadTupleList) {
        for (Messages.UnreadTuple unreadTuple : unreadTupleList) {
          AVIMMessage message = new AVIMMessage(unreadTuple.getCid(), unreadTuple.getFrom(), unreadTuple.getTimestamp(), -1);
          message.setMessageId(unreadTuple.getMid());
          message.setContent(unreadTuple.getData());
          message.setUpdateAt(unreadTuple.getPatchTimestamp());
          String conversationId = unreadTuple.getCid();
          AVInternalConversation conversation = session.getConversation(conversationId);
          conversation.onUnreadMessagesEvent(message, unreadTuple.getUnread());
        }
      }
    }
  }

  @Override
  public void onMessagePatchCommand(boolean isModify, Integer requestKey, Messages.PatchCommand patchCommand) {
    updateLocalPatchTime(isModify, patchCommand);
    if (isModify) {
      if (patchCommand.getPatchesCount() > 0) {
        for (Messages.PatchItem patchItem : patchCommand.getPatchesList()) {
          AVIMMessage message = AVIMTypedMessage.getMessage(patchItem.getCid(), patchItem.getMid(), patchItem.getData(), patchItem.getFrom(), patchItem.getTimestamp(), 0, 0);
          message.setUpdateAt(patchItem.getPatchTimestamp());
          AVInternalConversation conversation = session.getConversation(patchItem.getCid());
          conversation.onMessageUpdateEvent(message, patchItem.getRecall());
        }
      }
    } else {
      Operation op = session.conversationOperationCache.poll(requestKey);
      AVIMOperation operation = AVIMOperation.getAVIMOperation(op.operation);
      Bundle bundle = new Bundle();
      bundle.putLong(Conversation.PARAM_MESSAGE_PATCH_TIME, patchCommand.getLastPatchTime());
      BroadcastUtil.sendIMLocalBroadcast(session.getSelfPeerId(), null, requestKey, bundle, operation);
    }
  }

  private void updateLocalPatchTime(boolean isModify, Messages.PatchCommand patchCommand) {
    if (isModify) {
      long lastPatchTime = 0;
      for (Messages.PatchItem item : patchCommand.getPatchesList()) {
        if (item.getPatchTimestamp() > lastPatchTime) {
          lastPatchTime = item.getPatchTimestamp();
        }
      }
      session.updateLastPatchTime(lastPatchTime);
    } else {
      session.updateLastPatchTime(patchCommand.getLastPatchTime());
    }
  }
}
