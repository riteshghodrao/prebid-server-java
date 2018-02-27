package org.prebid.server.bidder.rubicon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.BidRequest.BidRequestBuilder;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Imp.ImpBuilder;
import com.iab.openrtb.request.Publisher;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.User;
import com.iab.openrtb.request.Video;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;
import org.prebid.server.VertxTest;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpCall;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.HttpResponse;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.bidder.rubicon.model.RubiconBannerExt;
import org.prebid.server.bidder.rubicon.model.RubiconBannerExtRp;
import org.prebid.server.bidder.rubicon.model.RubiconImpExt;
import org.prebid.server.bidder.rubicon.model.RubiconImpExtRp;
import org.prebid.server.bidder.rubicon.model.RubiconImpExtRpTrack;
import org.prebid.server.bidder.rubicon.model.RubiconPubExt;
import org.prebid.server.bidder.rubicon.model.RubiconPubExtRp;
import org.prebid.server.bidder.rubicon.model.RubiconSiteExt;
import org.prebid.server.bidder.rubicon.model.RubiconSiteExtRp;
import org.prebid.server.bidder.rubicon.model.RubiconUserExt;
import org.prebid.server.bidder.rubicon.model.RubiconUserExtRp;
import org.prebid.server.bidder.rubicon.model.RubiconVideoExt;
import org.prebid.server.bidder.rubicon.model.RubiconVideoExtRp;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtUser;
import org.prebid.server.proto.openrtb.ext.request.ExtUserDigiTrust;
import org.prebid.server.proto.openrtb.ext.request.rubicon.ExtImpRubicon;
import org.prebid.server.proto.openrtb.ext.request.rubicon.ExtImpRubicon.ExtImpRubiconBuilder;
import org.prebid.server.proto.openrtb.ext.request.rubicon.RubiconVideoParams;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.math.BigDecimal.ONE;
import static java.math.BigDecimal.ZERO;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.function.Function.identity;
import static org.assertj.core.api.Assertions.*;
import static org.prebid.server.proto.openrtb.ext.response.BidType.banner;
import static org.prebid.server.proto.openrtb.ext.response.BidType.video;

public class RubiconBidderTest extends VertxTest {

    private static final String ENDPOINT_URL = "http://rubiconproject.com/exchange.json?trk=prebid";
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";

    private RubiconBidder rubiconBidder;

    @Before
    public void setUp() {
        rubiconBidder = new RubiconBidder(ENDPOINT_URL, USERNAME, PASSWORD);
    }

    @Test
    public void creationShouldFailOnNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new RubiconBidder(null, null, null));
        assertThatNullPointerException().isThrownBy(() -> new RubiconBidder(ENDPOINT_URL, null, null));
        assertThatNullPointerException().isThrownBy(() -> new RubiconBidder(ENDPOINT_URL, USERNAME, null));
    }

    @Test
    public void creationShouldFailOnInvalidEndpointUrl() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RubiconBidder("invalid_url", USERNAME, PASSWORD));
    }

    @Test
    public void makeHttpRequestsShouldFillMethodAndUrlAndExpectedHeaders() {
        // given
        final BidRequest bidRequest = givenBidRequest(builder -> builder.banner(
                Banner.builder().format(singletonList(Format.builder().w(300).h(250).build())).build()));

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getValue()).hasSize(1).element(0).isNotNull()
                .returns(HttpMethod.POST, HttpRequest::getMethod)
                .returns(ENDPOINT_URL, HttpRequest::getUri);
        assertThat(result.getValue().get(0).getHeaders()).isNotNull()
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .containsOnly(
                        tuple(HttpHeaders.AUTHORIZATION.toString(), "Basic dXNlcm5hbWU6cGFzc3dvcmQ="),
                        tuple(HttpHeaders.CONTENT_TYPE.toString(), "application/json;charset=utf-8"),
                        tuple(HttpHeaders.ACCEPT.toString(), "application/json"),
                        tuple(HttpHeaders.USER_AGENT.toString(), "prebid-server/1.0"));
    }

    @Test
    public void makeHttpRequestsShouldFillImpExt() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                builder -> builder.video(Video.builder().build()),
                builder -> builder
                        .zoneId(4001)
                        .inventory(mapper.valueToTree(Inventory.of(singletonList("5-star"), singletonList("tech")))));

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .flatExtracting(BidRequest::getImp).doesNotContainNull()
                .extracting(Imp::getExt).doesNotContainNull()
                .extracting(ext -> mapper.treeToValue(ext, RubiconImpExt.class))
                .containsOnly(RubiconImpExt.of(RubiconImpExtRp.of(4001,
                        mapper.valueToTree(Inventory.of(singletonList("5-star"), singletonList("tech"))),
                        RubiconImpExtRpTrack.of("", ""))));
    }

    @Test
    public void makeHttpRequestsShouldFillBannerExtWithAltSizeIdsIfMoreThanOneSize() {
        // given
        final BidRequest bidRequest = givenBidRequest(builder -> builder.banner(Banner.builder()
                .format(asList(
                        Format.builder().w(300).h(250).build(),
                        Format.builder().w(250).h(360).build(),
                        Format.builder().w(300).h(600).build()))
                .build()));

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .flatExtracting(BidRequest::getImp).doesNotContainNull()
                .extracting(Imp::getBanner).doesNotContainNull()
                .extracting(Banner::getExt).doesNotContainNull()
                .extracting(ext -> mapper.treeToValue(ext, RubiconBannerExt.class))
                .extracting(RubiconBannerExt::getRp).doesNotContainNull()
                .extracting(RubiconBannerExtRp::getSizeId, RubiconBannerExtRp::getAltSizeIds)
                .containsOnly(tuple(15, asList(32, 10)));
    }

    @Test
    public void makeHttpRequestsShouldTolerateInvalidSizes() {
        // given
        final BidRequest bidRequest = givenBidRequest(builder -> builder.banner(Banner.builder()
                .format(asList(
                        Format.builder().w(123).h(456).build(),
                        Format.builder().w(789).h(123).build(),
                        Format.builder().w(300).h(250).build()))
                .build()));

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .flatExtracting(BidRequest::getImp).doesNotContainNull()
                .extracting(Imp::getBanner).doesNotContainNull()
                .extracting(Banner::getExt).doesNotContainNull()
                .extracting(ext -> mapper.treeToValue(ext, RubiconBannerExt.class))
                .extracting(RubiconBannerExt::getRp).doesNotContainNull()
                .extracting(RubiconBannerExtRp::getSizeId)
                .containsOnly(15);
    }

    @Test
    public void makeHttpRequestsShouldFillVideoExt() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                builder -> builder.video(Video.builder().build()),
                builder -> builder.video(RubiconVideoParams.builder().skip(5).skipdelay(10).sizeId(14).build()));

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .flatExtracting(BidRequest::getImp).doesNotContainNull()
                .extracting(Imp::getVideo).doesNotContainNull()
                .extracting(Video::getExt).doesNotContainNull()
                .extracting(ext -> mapper.treeToValue(ext, RubiconVideoExt.class))
                .containsOnly(RubiconVideoExt.of(5, 10, RubiconVideoExtRp.of(14)));
    }

    @Test
    public void makeHttpRequestsShouldFillVideoExtOnlyIfBothVideoAndBannerPresentInImp() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                builder -> builder
                        .banner(Banner.builder().format(singletonList(Format.builder().w(300).h(250).build())).build())
                        .video(Video.builder().build()),
                builder -> builder
                        .video(RubiconVideoParams.builder().skip(5).skipdelay(10).sizeId(14).build()));

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .flatExtracting(BidRequest::getImp).doesNotContainNull()
                .containsOnly(Imp.builder()
                        .banner(Banner.builder()
                                .format(singletonList(Format.builder().w(300).h(250).build())).build())
                        .video(Video.builder()
                                .ext(mapper.valueToTree(RubiconVideoExt.of(5, 10, RubiconVideoExtRp.of(14))))
                                .build())
                        .ext(mapper.valueToTree(RubiconImpExt.of(
                                RubiconImpExtRp.of(null, null, RubiconImpExtRpTrack.of("", "")))))
                        .build());
    }

    @Test
    public void makeHttpRequestsShouldFillUserExtIfUserAndVisitorPresent() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                builder -> builder.user(User.builder().build()),
                builder -> builder.video(Video.builder().build()),
                builder -> builder.visitor(mapper.valueToTree(
                        Visitor.of(singletonList("new"), singletonList("iphone")))));

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .extracting(BidRequest::getUser).doesNotContainNull()
                .containsOnly(User.builder()
                        .ext(mapper.valueToTree(RubiconUserExt.of(RubiconUserExtRp.of(mapper.valueToTree(
                                Visitor.of(singletonList("new"), singletonList("iphone")))), null)))
                        .build());
    }

    @Test
    public void makeHttpRequestsShouldFillUserExtIfUserAndDigiTrustPresent() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                builder -> builder.user(User.builder().ext(
                        mapper.valueToTree(ExtUser.of(ExtUserDigiTrust.of("id", 123, 0))))
                        .build()),
                builder -> builder.video(Video.builder().build()),
                Function.identity());
        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .extracting(BidRequest::getUser).doesNotContainNull()
                .containsOnly(User.builder()
                        .ext(mapper.valueToTree(
                                RubiconUserExt.of(null, ExtUserDigiTrust.of("id", 123, 0))))
                        .build());
    }

    @Test
    public void makeHttpRequestsShouldNotChangeUserVisitorIsNotPresent() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                builder -> builder.user(User.builder().build()),
                builder -> builder.video(Video.builder().build()),
                identity());

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .extracting(BidRequest::getUser).doesNotContainNull()
                .containsOnly(User.builder().build());
    }

    @Test
    public void makeHttpRequestsShouldFillDeviceExtIfDevicePresent() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                builder -> builder.device(Device.builder().pxratio(BigDecimal.valueOf(4.2)).build()),
                builder -> builder.video(Video.builder().build()),
                identity());

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then

        // created manually, because mapper creates Double ObjectNode instead of BigDecimal
        // for floating point numbers (affects testing only)
        final ObjectNode rp = mapper.createObjectNode();
        rp.set("rp", mapper.createObjectNode().put("pixelratio", new Double("4.2")));

        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .extracting(BidRequest::getDevice).doesNotContainNull()
                .containsOnly(Device.builder()
                        .pxratio(BigDecimal.valueOf(4.2))
                        .ext(rp)
                        .build());
    }

    @Test
    public void makeHttpRequestsShouldFillSiteExtIfSitePresent() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                builder -> builder.site(Site.builder().build()),
                builder -> builder.video(Video.builder().build()),
                builder -> builder.accountId(2001).siteId(3001));

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .extracting(BidRequest::getSite).doesNotContainNull()
                .containsOnly(Site.builder()
                        .publisher(Publisher.builder()
                                .ext(mapper.valueToTree(RubiconPubExt.of(RubiconPubExtRp.of(2001))))
                                .build())
                        .ext(mapper.valueToTree(RubiconSiteExt.of(RubiconSiteExtRp.of(3001))))
                        .build());
    }

    @Test
    public void makeHttpRequestsShouldFillAppExtIfAppPresent() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                builder -> builder.app(App.builder().build()),
                builder -> builder.video(Video.builder().build()),
                builder -> builder.accountId(2001).siteId(3001));

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .extracting(BidRequest::getApp).doesNotContainNull()
                .containsOnly(App.builder()
                        .publisher(Publisher.builder()
                                .ext(mapper.valueToTree(RubiconPubExt.of(RubiconPubExtRp.of(2001))))
                                .build())
                        .ext(mapper.valueToTree(RubiconSiteExt.of(RubiconSiteExtRp.of(3001))))
                        .build());
    }

    @Test
    public void makeHttpRequestsShouldCreateRequestPerImp() {
        // given
        final Imp imp = givenImp(builder -> builder.video(Video.builder().build()));
        final BidRequest bidRequest = BidRequest.builder().imp(asList(imp, imp)).build();

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        final BidRequest expectedBidRequest = BidRequest.builder()
                .imp(singletonList(Imp.builder()
                        .video(Video.builder().build())
                        .ext(mapper.valueToTree(RubiconImpExt.of(
                                RubiconImpExtRp.of(null, null, RubiconImpExtRpTrack.of("", "")))))
                        .build()))
                .build();

        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(2).doesNotContainNull()
                .extracting(httpRequest -> mapper.readValue(httpRequest.getBody(), BidRequest.class))
                .containsOnly(expectedBidRequest, expectedBidRequest);
    }

    @Test
    public void makeHttpRequestsShouldReturnErrorIfImpExtCouldNotBeParsed() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .imp(asList(
                        givenImp(builder -> builder.video(Video.builder().build())),
                        Imp.builder()
                                .ext(mapper.valueToTree(ExtPrebid.of(null, mapper.createArrayNode())))
                                .build()))
                .build();

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessage()).startsWith("Cannot deserialize instance");
        assertThat(result.getValue()).hasSize(1);
    }

    @Test
    public void makeHttpRequestsShouldReturnErrorIfNoValidSizes() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .imp(asList(
                        givenImp(builder -> builder.video(Video.builder().build())),
                        givenImp(builder -> builder.banner(Banner.builder()
                                .format(singletonList(Format.builder().w(123).h(456).build()))
                                .build()))))
                .build();

        // when
        final Result<List<HttpRequest>> result = rubiconBidder.makeHttpRequests(bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).hasSize(1);
        Assertions.assertThat(result.getErrors()).extracting(BidderError::getMessage).containsOnly("No valid sizes");
        assertThat(result.getValue()).hasSize(1);
    }

    @Test
    public void makeBidsShouldReturnEmptyResultIfResponseStatusIs204() {
        // given
        final BidRequest bidRequest = givenBidRequest(identity());
        final HttpCall httpCall = givenHttpCall(204, null);

        // when
        final Result<List<BidderBid>> result = rubiconBidder.makeBids(httpCall, bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).isEmpty();
    }

    @Test
    public void makeBidsShouldReturnErrorIfResponseStatusIsNot200Or204() {
        // given
        final BidRequest bidRequest = givenBidRequest(identity());
        final HttpCall httpCall = givenHttpCall(302, null);

        // when
        final Result<List<BidderBid>> result = rubiconBidder.makeBids(httpCall, bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).extracting(BidderError::getMessage)
                .containsOnly("Unexpected status code: 302. Run with request.test = 1 for more info");
        assertThat(result.getValue()).isEmpty();
    }

    @Test
    public void makeBidsShouldReturnErrorIfResponseBodyCouldNotBeParsed() {
        // given
        final BidRequest bidRequest = givenBidRequest(identity());
        final HttpCall httpCall = givenHttpCall(200, "invalid");

        // when
        final Result<List<BidderBid>> result = rubiconBidder.makeBids(httpCall, bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessage()).startsWith("Unrecognized token");
        assertThat(result.getValue()).isEmpty();
    }

    @Test
    public void makeBidsShouldReturnBannerBidIfNoMatchingImp() throws JsonProcessingException {
        // given
        final BidRequest bidRequest = givenBidRequest(identity());
        final HttpCall httpCall = givenHttpCall(200, givenBidResponse("impId1", ONE));

        // when
        final Result<List<BidderBid>> result = rubiconBidder.makeBids(httpCall, bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .containsOnly(BidderBid.of(Bid.builder().impid("impId1").price(ONE).build(), banner));
    }

    @Test
    public void makeBidsShouldReturnBannerBidIfMatchingImpHasNoVideo() throws JsonProcessingException {
        // given
        final BidRequest bidRequest = givenBidRequest(builder -> builder.id("impId"));
        final HttpCall httpCall = givenHttpCall(200, givenBidResponse("impId", ONE));

        // when
        final Result<List<BidderBid>> result = rubiconBidder.makeBids(httpCall, bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .containsOnly(BidderBid.of(Bid.builder().impid("impId").price(ONE).build(), banner));
    }

    @Test
    public void makeBidsShouldReturnVideoBidIfMatchingImpHasVideo() throws JsonProcessingException {
        // given
        final BidRequest bidRequest = givenBidRequest(builder -> builder.id("impId").video(Video.builder().build()));
        final HttpCall httpCall = givenHttpCall(200, givenBidResponse("impId", ONE));

        // when
        final Result<List<BidderBid>> result = rubiconBidder.makeBids(httpCall, bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .containsOnly(BidderBid.of(Bid.builder().impid("impId").price(ONE).build(), video));
    }

    @Test
    public void makeBidsShouldNotReturnImpIfPriceLessOrEqualToZero() throws JsonProcessingException {
        // given
        final BidRequest bidRequest = givenBidRequest(builder -> builder.id("impId").video(Video.builder().build()));
        final HttpCall httpCall = givenHttpCall(200, givenBidResponse("impId", ZERO));

        // when
        final Result<List<BidderBid>> result = rubiconBidder.makeBids(httpCall, bidRequest);

        // then
        Assertions.assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).isEmpty();
    }

    private static BidRequest givenBidRequest(Function<BidRequestBuilder, BidRequestBuilder> bidRequestCustomizer,
                                              Function<ImpBuilder, ImpBuilder> impCustomizer,
                                              Function<ExtImpRubiconBuilder, ExtImpRubiconBuilder> extCustomizer) {
        return bidRequestCustomizer.apply(BidRequest.builder()
                .imp(singletonList(givenImp(impCustomizer, extCustomizer))))
                .build();
    }

    private static BidRequest givenBidRequest(Function<ImpBuilder, ImpBuilder> impCustomizer) {
        return givenBidRequest(identity(), impCustomizer, identity());
    }

    private static BidRequest givenBidRequest(Function<ImpBuilder, ImpBuilder> impCustomizer,
                                              Function<ExtImpRubiconBuilder, ExtImpRubiconBuilder> extCustomizer) {
        return givenBidRequest(identity(), impCustomizer, extCustomizer);
    }

    private static Imp givenImp(Function<ImpBuilder, ImpBuilder> impCustomizer,
                                Function<ExtImpRubiconBuilder, ExtImpRubiconBuilder> extCustomizer) {
        return impCustomizer.apply(Imp.builder()
                .ext(mapper.valueToTree(ExtPrebid.of(null, extCustomizer.apply(ExtImpRubicon.builder()).build()))))
                .build();
    }

    private static Imp givenImp(Function<ImpBuilder, ImpBuilder> impCustomizer) {
        return givenImp(impCustomizer, identity());
    }

    private static HttpCall givenHttpCall(int statusCode, String body) {
        return HttpCall.full(null, HttpResponse.of(statusCode, null, body), null);
    }

    private static String givenBidResponse(String impId, BigDecimal price) throws JsonProcessingException {
        return mapper.writeValueAsString(BidResponse.builder()
                .seatbid(singletonList(SeatBid.builder()
                        .bid(singletonList(Bid.builder()
                                .impid(impId)
                                .price(price)
                                .build()))
                        .build()))
                .build());
    }

    @AllArgsConstructor(staticName = "of")
    @Value
    private static final class Inventory {

        List<String> rating;

        List<String> prodtype;
    }

    @AllArgsConstructor(staticName = "of")
    @Value
    private static final class Visitor {

        List<String> ucat;

        List<String> search;
    }
}