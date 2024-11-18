package org.prebid.server.bidder.inmobi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.User;
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
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebidSdkRenderer;
import org.prebid.server.proto.openrtb.ext.request.inmobi.ExtImpInmobi;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebid;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebidMeta;
import org.prebid.server.proto.openrtb.ext.response.ExtBidResponse;
import org.prebid.server.proto.openrtb.ext.response.ExtBidResponsePrebid;
import org.prebid.server.util.BidderUtil;
import org.prebid.server.util.HttpUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class InmobiBidder implements Bidder<BidRequest> {

    private static final TypeReference<ExtPrebid<?, ExtImpInmobi>> INMOBI_EXT_TYPE_REFERENCE =
            new TypeReference<>() {
            };
    private static final TypeReference<ExtPrebid<ExtBidPrebid, ObjectNode>> EXT_PREBID_TYPE_REFERENCE =
            new TypeReference<>() {
            };
    private static final int FIRST_IMP_INDEX = 0;

    private final String endpointUrl;
    private final JacksonMapper mapper;
    private final String inMobiSdkRendererName = "InMobiRenderer";
    private final String inMobiSdkRendererVersion = "10.7.5";
    private final String inMobiSdkTokenKey = "token";

    public InmobiBidder(String endpointUrl, JacksonMapper mapper) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest request) {
        final List<BidderError> errors = new ArrayList<>();

        final Imp imp = request.getImp().getFirst();
        final ExtImpInmobi extImpInmobi;

        try {
            extImpInmobi = parseImpExt(imp);
        } catch (PreBidException e) {
            return Result.withError(BidderError.badInput("bad InMobi bidder ext"));
        }

        final String plcId = extImpInmobi.getPlc();
        if (StringUtils.isBlank(plcId)) {
            return Result.withError(BidderError.badInput("'plc' is a required attribute for InMobi's bidder ext"));
        }

        final List<Imp> updatedImps = new ArrayList<>(request.getImp());
        updatedImps.set(FIRST_IMP_INDEX, updateImp(imp, plcId));

        User updatedUser = updateUserNode(request);
        Device updatedDevice = overwriteDeviceIp(request);

        System.out.println("InMobi User " + updatedUser);
        System.out.println("InMobi Device " + request.getDevice());

        final BidRequest outgoingRequest = request.toBuilder().imp(updatedImps).user(updatedUser).device(updatedDevice).build();

        System.out.println("InMobi Outgoing " + outgoingRequest);
        return Result.of(Collections.singletonList(BidderUtil.defaultRequest(outgoingRequest, endpointUrl, mapper)),
                errors);
    }

    private Device overwriteDeviceIp(BidRequest request){
        Device device = request.getDevice();
        return device.toBuilder().ip("122.172.86.88").build();
    }


    private User updateUserNode(BidRequest bidRequest){
        User user = bidRequest.getUser();
        String inMobiSdkToken = fetchSdkToken(bidRequest);
        if(inMobiSdkToken != null){
            if(user == null){
                System.out.println("creating user and adding token");
                user = User.builder().buyeruid(inMobiSdkToken).build();
            }else{
                System.out.println("adding token");
                user = user.toBuilder().buyeruid(inMobiSdkToken).build();
            }
        }
        return user;
    }

    private ExtImpInmobi parseImpExt(Imp imp) {
        try {
            return mapper.mapper().convertValue(imp.getExt(), INMOBI_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage(), e);
        }
    }

    private String fetchSdkToken(BidRequest request){
        try {
            ExtRequest extRequest = request.getExt();
            List<ExtRequestPrebidSdkRenderer> renderers = extRequest.getPrebid().getSdk().getRenderers();
            for (ExtRequestPrebidSdkRenderer renderer : renderers) {
                if (renderer.getName().equalsIgnoreCase(inMobiSdkRendererName)) {
                    System.out.println("Found InMobi SDK");
                    return extractSdkToken(renderer.getData());
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("Error while finding InMobi SDK token" + e);
            return null;
        }
    }

    private String extractSdkToken(JsonNode sdkData) {
        System.out.println(sdkData);
        return sdkData.has(inMobiSdkTokenKey) ? sdkData.get(inMobiSdkTokenKey).asText() : null;
    }

    private Imp updateImp(Imp imp, String plcId) {
        final Banner banner = imp.getBanner();
        if (banner != null) {
            if ((banner.getW() == null || banner.getH() == null || banner.getW() == 0 || banner.getH() == 0)
                    && CollectionUtils.isNotEmpty(banner.getFormat())) {
                final Format format = banner.getFormat().getFirst();
                return imp.toBuilder().banner(banner.toBuilder().w(format.getW()).h(format.getH()).build()).tagid(plcId).build();
            }
        }
        return imp;
    }

    @Override
    public final Result<List<BidderBid>> makeBids(BidderCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final BidResponse bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            return Result.of(extractBids(httpCall.getRequest().getPayload(), bidResponse), Collections.emptyList());
        } catch (DecodeException | PreBidException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    //TODO: since we are using /ortb/imsdk path, there is no ext or prebid object
    private Bid updateBid(Bid bid) {
        // TODO: ideally should be read from response but hard coding for now
        List<String> advertiserDomains = new ArrayList<>();
        advertiserDomains.add("inmobi.com");

        final ExtBidPrebidMeta modifiedMeta = ExtBidPrebidMeta.builder()
                .rendererName(inMobiSdkRendererName)
                .rendererVersion(inMobiSdkRendererVersion)
                .advertiserDomains(advertiserDomains)
                .networkName("inmobi")
                .build();
        final ExtBidPrebid modifiedPrebid = ExtBidPrebid.builder().meta(modifiedMeta).build();
        final ObjectNode modifiedBidExt = mapper.mapper().valueToTree(ExtPrebid.of(modifiedPrebid, null));
        return bid.toBuilder().ext(modifiedBidExt).build();

    }

    private ExtBidPrebid parseExtBidPrebidMeta(Bid bid) {
        try {
            return mapper.mapper().convertValue(bid.getExt(), EXT_PREBID_TYPE_REFERENCE).getPrebid();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage());
        }
    }

    private List<BidderBid> extractBids(BidRequest bidRequest, BidResponse bidResponse) {
        if (bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            return Collections.emptyList();
        }
        return bidsFromResponse(bidRequest, bidResponse);
    }

    private List<BidderBid> bidsFromResponse(BidRequest bidRequest, BidResponse bidResponse) {
        return bidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(SeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(this::updateBid)
                .map(bid -> BidderBid.of(bid, getBidType(bid.getImpid(), bidRequest.getImp()), bidResponse.getCur()))
                .toList();
    }

    private static BidType getBidType(String impId, List<Imp> imps) {
        for (Imp imp : imps) {
            if (imp.getId().equals(impId)) {
                if (imp.getVideo() != null) {
                    return BidType.video;
                }
                if (imp.getXNative() != null) {
                    return BidType.xNative;
                }
            }
        }
        return BidType.banner;
    }
}
