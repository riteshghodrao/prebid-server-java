package org.prebid.server.handler;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.Future;
import org.prebid.server.auction.BidResponsePostProcessor;
import org.prebid.server.cookie.UidsCookie;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.model.HttpRequestContext;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebid;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebidMeta;
import org.prebid.server.settings.model.Account;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.prebid.server.auction.VideoResponseFactory.PREBID_EXT;

@Primary
public class InMobiResponseHandler implements BidResponsePostProcessor {

    InMobiTemplateHandler inMobiTemplateHandler = new InMobiTemplateHandler();
    private static final String inMobiSeatName = "inmobi";
    private static final boolean FORCE_RENDERING_TO_INMOBI = true;
    private final String inMobiSdkRendererName = "InMobiRenderer";
    private final String inMobiSdkRendererVersion = "10.7.5";

    ObjectMapper mapper = new ObjectMapper();


    @Override
    public Future<BidResponse> postProcess(HttpRequestContext httpRequest, UidsCookie uidsCookie,
                                           BidRequest bidRequest,
                                           BidResponse bidResponse, Account account) {
        System.out.println("entered inmobi post process ");
        return addInMobiTemplate(bidResponse);
//        return Future.succeededFuture(bidResponse);
    }

    private boolean isOpenRtbResponse(){
        return false;
    }


    private Future<BidResponse> addInMobiTemplate(BidResponse bidResponse){
        try {
            List<SeatBid> newSeatBids = getUpdatedSeatbids(bidResponse.getSeatbid());
            BidResponse newBidResponse = bidResponse.toBuilder().seatbid(newSeatBids).build();
            return Future.succeededFuture(newBidResponse);
        } catch (Exception e) {
            System.out.println(e);
            e.printStackTrace();
        }
        return Future.succeededFuture(bidResponse);
    }

    private List<SeatBid> getUpdatedSeatbids(List<SeatBid> seatBids ){
        List<SeatBid> updatedSeatBids = new ArrayList<>();
        for(SeatBid seatBid : seatBids){
            System.out.println("SEATBIDS");
            System.out.println(seatBid.getSeat());
            //TODO: add more condition to check
            if(seatBid.getSeat().equalsIgnoreCase(inMobiSeatName)){
                // assuming InMobi response is already templatised
                System.out.println("INMOBI SEAT ID found");
                updatedSeatBids.add(seatBid);
            }else {
                Bid newBid = getUpdatedBid(seatBid.getBid().getFirst());
                SeatBid newSeatbid = seatBid.toBuilder().bid(new ArrayList<>(Arrays.asList(newBid))).build();
                updatedSeatBids.add(newSeatbid);
            }
        }
        return updatedSeatBids;

    }

    private Bid getUpdatedBid(Bid bid){
        //TODO: update this condition
        if(bid.getAdm().contains("pubContent")){
            System.out.println("InMobi template detected already");
            return bid;
        }

        String resultHtml = inMobiTemplateHandler.generateTemplateResponse(bid.getAdm());
        Bid newBid = bid.toBuilder().adm(resultHtml).build();
        if(FORCE_RENDERING_TO_INMOBI){
            return getInMobiRenderingParams(newBid);
        }else{
            return newBid;
        }
    }

    private Bid getInMobiRenderingParams(Bid bid){
//        ObjectMapper objectMapper1 = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        List<String> advertiserDomains = new ArrayList<>();
        advertiserDomains.add("inmobi.com");

        final ExtBidPrebidMeta modifiedMeta = ExtBidPrebidMeta.builder()
                .rendererName(inMobiSdkRendererName)
                .rendererVersion(inMobiSdkRendererVersion)
                .advertiserDomains(advertiserDomains)
                .networkName("inmobi")
                .build();
        // TODO: use ad type

        final ExtBidPrebid.ExtBidPrebidBuilder extBidPrebidBuilder = getExtPrebid(bid.getExt(), ExtBidPrebid.class)
                .map(ExtBidPrebid::toBuilder)
                .orElseGet(ExtBidPrebid::builder);

//        String adType = bid.getExt().get("prebid").get("type").toString();
        final ExtBidPrebid modifiedPrebid = extBidPrebidBuilder.meta(modifiedMeta).build();
        final ObjectNode modifiedBidExt = mapper.valueToTree(ExtPrebid.of(modifiedPrebid, null));
        return bid.toBuilder().ext(modifiedBidExt).build();
    }


    private <T> Optional<T> getExtPrebid(ObjectNode extNode, Class<T> extClass) {
        return Optional.ofNullable(extNode)
                .filter(ext -> ext.hasNonNull(PREBID_EXT))
                .map(ext -> convertValue(extNode, PREBID_EXT, extClass));
    }

    private <T> T convertValue(JsonNode jsonNode, String key, Class<T> typeClass) {
        try {
            return mapper.convertValue(jsonNode.get(key), typeClass);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

}
