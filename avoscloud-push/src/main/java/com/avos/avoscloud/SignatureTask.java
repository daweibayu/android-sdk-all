package com.avos.avoscloud;

import android.os.AsyncTask;

/**
 * Created by wli on 2017/7/26.
 */
class SignatureTask extends AsyncTask<String, Integer, Signature> {
  SignatureCallback callback;
  AVException signatureException;

  public SignatureTask(SignatureCallback callback) {
    this.callback = callback;
  }

  @Override
  protected Signature doInBackground(String... params) {
    String clientId = params[0];
    Signature signature;
    if (callback.useSignatureCache()) {
      signature = AVSessionCacheHelper.SignatureCache.getSessionSignature(clientId);
      if (signature != null && !signature.isExpired()) {
        if (AVOSCloud.isDebugLogEnabled()) {
          LogUtil.avlog.d("get signature from cache");
        }
        return signature;
      } else {
        if (AVOSCloud.isDebugLogEnabled()) {
          LogUtil.avlog.d("signature expired");
        }
      }
    }
    try {
      signature = callback.computeSignature();
      if (callback.cacheSignature()) {
        AVSessionCacheHelper.SignatureCache.addSessionSignature(clientId, signature);
      }
      return signature;
    } catch (Exception e) {
      signatureException = new AVException(e);
      return null;
    }
  }

  @Override
  protected void onPostExecute(Signature result) {
    callback.onSignatureReady(result, signatureException);
  }
}