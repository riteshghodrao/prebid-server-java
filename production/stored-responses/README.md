# Stored Responses Directory

This directory contains pre-defined bid responses that can be returned without actually calling bidder adapters.

## Purpose
Stored responses are useful for:
- Testing and development (return mock responses)
- Debugging bidder integrations
- Simulating bid responses when adapters are unavailable
- Implementing specific business logic that requires predetermined responses

## File Format
- Files should be valid OpenRTB bid response objects
- Filename typically corresponds to the stored response ID (e.g., `test_auction_response.json`)
- The JSON should contain a complete bid response including seatbid, bid objects, etc.

## Usage
When making a bid request, you can force a stored response by including:
```json
{
  "ext": {
    "prebid": {
      "storedauctionresponse": {
        "id": "test_auction_response"
      }
    }
  }
}
```

Or for stored bidder responses (individual bidder level):
```json
{
  "ext": {
    "prebid": {
      "storedbidresponse": {
        "bidder": "appnexus",
        "id": "test_bidder_response"
      }
    }
  }
}
```

The server will return the stored response instead of making actual bidder calls.
