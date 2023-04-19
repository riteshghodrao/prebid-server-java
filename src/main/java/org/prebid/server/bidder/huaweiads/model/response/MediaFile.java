package org.prebid.server.bidder.huaweiads.model.response;

import lombok.Value;

@Value(staticConstructor = "of")
public class MediaFile {

    String mime;

    Long width;

    Long height;

    Long fileSize;

    String url;

    String sha256;

}
