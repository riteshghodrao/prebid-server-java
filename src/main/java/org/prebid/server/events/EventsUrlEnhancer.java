package org.prebid.server.events;

import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.Bid;
import org.prebid.server.log.Logger;
import org.prebid.server.log.LoggerFactory;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.proto.openrtb.ext.response.Events;

import java.util.List;

public final class EventsUrlEnhancer {

    private static final Logger logger = LoggerFactory.getLogger(EventsUrlEnhancer.class);

    private EventsUrlEnhancer() {
    }

    public static Events enhance(Events events, Bid bid, BidType bidType, List<Imp> imps) {
        if (events == null) {
            logger.debug("EventsUrlEnhancer: base events null for bidId={}, skipping", bid.getId());
            return null;
        }

        final StringBuilder extra = new StringBuilder();
        if (bid.getPrice() != null) {
            extra.append("&p=").append(bid.getPrice().toPlainString());
        }
        if (bidType != null) {
            extra.append("&mtype=").append(bidType.getName());
        }

        final Imp matchingImp = imps.stream()
                .filter(imp -> imp.getId().equals(bid.getImpid()))
                .findFirst()
                .orElse(null);

        if (matchingImp != null) {
            if (matchingImp.getTagid() != null
                    && !matchingImp.getTagid().isBlank()) {
                extra.append("&tag=").append(matchingImp.getTagid());
            }
            final String size = resolveImpSize(matchingImp, bidType);
            if (size != null) {
                extra.append("&size=").append(size);
            }
        }

        if (extra.isEmpty()) {
            return events;
        }

        final String suffix = extra.toString();
        final Events enhanced = Events.of(
                events.getWin() != null ? events.getWin() + suffix : null,
                events.getImp() != null ? events.getImp() + suffix : null);
        logger.debug("EventsUrlEnhancer: bidId={}, suffix={}", bid.getId(), suffix);
        return enhanced;
    }

    private static String resolveImpSize(Imp imp, BidType bidType) {
        if (bidType == BidType.banner && imp.getBanner() != null
                && imp.getBanner().getW() != null
                && imp.getBanner().getH() != null) {
            return imp.getBanner().getW() + "x" + imp.getBanner().getH();
        }
        if (bidType == BidType.video && imp.getVideo() != null
                && imp.getVideo().getW() != null
                && imp.getVideo().getH() != null) {
            return imp.getVideo().getW() + "x" + imp.getVideo().getH();
        }
        return null;
    }
}
