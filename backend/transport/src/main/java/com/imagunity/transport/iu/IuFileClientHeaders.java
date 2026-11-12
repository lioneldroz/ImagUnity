package com.imagunity.transport.iu;

import com.imagunity.protocol.SignedRequest;
import java.net.http.HttpRequest;

/** En-têtes d'une requête signée IU-T. */
final class IuFileClientHeaders {

    private IuFileClientHeaders() {
    }

    static HttpRequest.Builder apply(HttpRequest.Builder builder, SignedRequest signed, String keyId,
                                     String signature) {
        return builder.header(SignedRequest.KEY_ID, keyId)
                .header(SignedRequest.TIMESTAMP, Long.toString(signed.timestamp()))
                .header(SignedRequest.REQUEST_ID, signed.requestId())
                .header(SignedRequest.SIGNATURE, signature);
    }
}
