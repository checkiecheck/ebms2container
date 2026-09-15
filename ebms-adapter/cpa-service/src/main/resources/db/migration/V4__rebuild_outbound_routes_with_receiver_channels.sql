-- Outbound routes are derived from the CPA XML. V3 initially selected the local
-- CanSend channel; outbound delivery must use the paired partner CanReceive channel.
-- Clearing the derived rows triggers the locked lazy rebuild on the next route lookup.
DELETE FROM cpa_outbound_route;
