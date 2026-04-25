package org.prebid.server.bidder.inmobi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderCall;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.inmobi.ExtImpInmobi;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.log.Logger;
import org.prebid.server.log.LoggerFactory;
import org.prebid.server.util.BidderUtil;
import org.prebid.server.util.HttpUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class InmobiBidder implements Bidder<BidRequest> {

    private static final Logger logger = LoggerFactory.getLogger(InmobiBidder.class);
    private static final TypeReference<ExtPrebid<?, ExtImpInmobi>> INMOBI_EXT_TYPE_REFERENCE =
            new TypeReference<>() {
            };
    private final String endpointUrl;
    private final JacksonMapper mapper;

    public InmobiBidder(String endpointUrl, JacksonMapper mapper) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest request) {
        final List<BidderError> errors = new ArrayList<>();
        final List<HttpRequest<BidRequest>> httpRequests = new ArrayList<>();
        logger.debug("InMobi makeHttpRequests: requestId={}, impCount={}",
                request.getId(), request.getImp().size());

        for (Imp imp : request.getImp()) {
            final ExtImpInmobi extImpInmobi;
            try {
                extImpInmobi = parseImpExt(imp);
            } catch (PreBidException e) {
                logger.debug("InMobi imp={}: failed to parse ext: {}", imp.getId(), e.getMessage());
                errors.add(BidderError.badInput(
                        "bad InMobi bidder ext for imp: " + imp.getId()));
                continue;
            }

            if (StringUtils.isBlank(extImpInmobi.getPlc())) {
                logger.debug("InMobi imp={}: missing 'plc' in ext", imp.getId());
                errors.add(BidderError.badInput(
                        "'plc' is required for InMobi imp: " + imp.getId()));
                continue;
            }

            final BidRequest singleImpRequest = request.toBuilder()
                    .imp(Collections.singletonList(updateImp(imp)))
                    .build();
            try {
                logger.debug("InMobi outgoing request imp={}, plc={}:\n{}",
                        imp.getId(), extImpInmobi.getPlc(),
                        mapper.mapper().writerWithDefaultPrettyPrinter()
                                .writeValueAsString(singleImpRequest));
            } catch (Exception ignored) {
            }

            httpRequests.add(BidderUtil.defaultRequest(
                    singleImpRequest, endpointUrl, mapper));
        }

        logger.debug("InMobi: produced {} HTTP requests, {} errors",
                httpRequests.size(), errors.size());
        return Result.of(httpRequests, errors);
    }

    private ExtImpInmobi parseImpExt(Imp imp) {
        try {
            return mapper.mapper().convertValue(imp.getExt(), INMOBI_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage(), e);
        }
    }

    private Imp updateImp(Imp imp) {
        final Banner banner = imp.getBanner();
        if (banner != null) {
            if ((banner.getW() == null || banner.getH() == null || banner.getW() == 0 || banner.getH() == 0)
                    && CollectionUtils.isNotEmpty(banner.getFormat())) {
                final Format format = banner.getFormat().getFirst();
                return imp.toBuilder().banner(banner.toBuilder().w(format.getW()).h(format.getH()).build()).build();
            }
        }
        return imp;
    }

    @Override
    public final Result<List<BidderBid>> makeBids(BidderCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final BidResponse bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            final List<BidderBid> bids = extractBids(bidResponse);
            return Result.of(bids, Collections.emptyList());
        } catch (DecodeException | PreBidException e) {
            logger.debug("InMobi makeBids: error parsing response: {}", e.getMessage());
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private List<BidderBid> extractBids(BidResponse bidResponse) {
        if (bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            return Collections.emptyList();
        }
        return bidsFromResponse(bidResponse);
    }

    private List<BidderBid> bidsFromResponse(BidResponse bidResponse) {
        return bidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(SeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(bid -> BidderBid.of(bid, getBidType(bid), bidResponse.getCur()))
                .toList();
    }

    private static BidType getBidType(Bid bid) {
        final Integer mtype = bid.getMtype();
        return switch (mtype) {
            case 1 -> BidType.banner;
            case 2 -> BidType.video;
            case 4 -> BidType.xNative;
            case null, default -> throw new PreBidException("Unsupported mtype %d for bid %s"
                    .formatted(mtype, bid.getId()));
        };
    }
}
