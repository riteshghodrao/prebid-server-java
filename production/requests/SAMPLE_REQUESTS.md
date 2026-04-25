# Sample Request Bodies for Stored Requests and Impressions

This document provides example request bodies for using the stored requests and stored impressions configured in the production setup.

## Stored Requests and Impressions Available

### Stored Request
- **ID**: `main-banner-request`
- **Contains**: References to 4 stored impressions (320x50, 300x250, 160x600, 300x600)

### Stored Impressions
- **ID**: `banner_320x50` - Mobile banner (320x50)
- **ID**: `banner_300x250` - Medium rectangle (300x250)
- **ID**: `banner_160x600` - Skyscraper (160x600)
- **ID**: `banner_300x600` - Half page (300x600)

## Sample Request Bodies

### 1. Using Stored Request (Simplest - All 4 Ad Sizes)

This request uses the stored request which automatically includes all 4 stored impressions:

```json
{
  "id": "test-request-1",
  "ext": {
    "prebid": {
      "storedrequest": {
        "id": "main-banner-request"
      }
    }
  },
  "site": {
    "page": "https://example.com/page",
    "publisher": {
      "id": "1001"
    }
  },
  "device": {
    "ua": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "ip": "192.168.1.1"
  },
  "user": {
    "id": "user123"
  }
}
```

**What this does:**
- Loads `main-banner-request` stored request
- Automatically includes all 4 stored impressions (320x50, 300x250, 160x600, 300x600)
- Each impression has bidder configurations (AppNexus, Pubmatic, Rubicon)
- Each impression references stored responses for testing

### 2. Using Single Stored Impression

Request a single ad size using stored impression:

```json
{
  "id": "test-request-2",
  "imp": [
    {
      "id": "imp1",
      "ext": {
        "prebid": {
          "storedimp": {
            "id": "banner_300x250"
          }
        }
      }
    }
  ],
  "site": {
    "page": "https://example.com/page",
    "publisher": {
      "id": "1001"
    }
  },
  "device": {
    "ua": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "ip": "192.168.1.1"
  }
}
```

**What this does:**
- Loads only the `banner_300x250` stored impression
- Includes bidder configurations for 300x250 size
- Returns stored response for testing

### 3. Using Multiple Stored Impressions

Request multiple specific ad sizes:

```json
{
  "id": "test-request-3",
  "imp": [
    {
      "id": "imp1",
      "ext": {
        "prebid": {
          "storedimp": {
            "id": "banner_320x50"
          }
        }
      }
    },
    {
      "id": "imp2",
      "ext": {
        "prebid": {
          "storedimp": {
            "id": "banner_300x250"
          }
        }
      }
    }
  ],
  "site": {
    "page": "https://example.com/mobile-page",
    "publisher": {
      "id": "1001"
    }
  },
  "device": {
    "ua": "Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X)",
    "ip": "192.168.1.1"
  }
}
```

**What this does:**
- Loads two stored impressions: 320x50 and 300x250
- Useful for mobile pages where you want both mobile banner and medium rectangle

### 4. Complete Request with All Fields

Full request with all optional fields:

```json
{
  "id": "test-request-4",
  "ext": {
    "prebid": {
      "storedrequest": {
        "id": "main-banner-request"
      },
      "targeting": {
        "pricegranularity": {
          "precision": 2,
          "ranges": [
            {
              "max": 20,
              "increment": 0.1
            }
          ]
        }
      },
      "cache": {
        "bids": {}
      }
    }
  },
  "site": {
    "domain": "example.com",
    "page": "https://example.com/article",
    "publisher": {
      "id": "1001",
      "name": "Example Publisher"
    },
    "ref": "https://google.com"
  },
  "device": {
    "ua": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "ip": "192.168.1.1",
    "geo": {
      "country": "US",
      "region": "CA",
      "city": "San Francisco"
    }
  },
  "user": {
    "id": "user123",
    "buyeruid": "buyer-uid-123"
  },
  "regs": {
    "ext": {
      "gdpr": 0,
      "us_privacy": "1YNN"
    }
  },
  "at": 1,
  "tmax": 5000,
  "cur": ["USD"]
}
```

### 5. Request with Custom Impressions (Override Stored)

You can also mix stored impressions with custom ones:

```json
{
  "id": "test-request-5",
  "imp": [
    {
      "id": "imp1",
      "ext": {
        "prebid": {
          "storedimp": {
            "id": "banner_300x250"
          }
        }
      }
    },
    {
      "id": "imp2",
      "banner": {
        "format": [
          {
            "w": 728,
            "h": 90
          }
        ]
      },
      "ext": {
        "prebid": {
          "bidder": {
            "appnexus": {
              "placementId": 13144370
            }
          }
        }
      }
    }
  ],
  "site": {
    "page": "https://example.com/page",
    "publisher": {
      "id": "1001"
    }
  }
}
```

## Testing with cURL

### Test Stored Request

```bash
curl -X POST http://localhost:8080/openrtb2/auction \
  -H "Content-Type: application/json" \
  -d '{
    "id": "test-request",
    "ext": {
      "prebid": {
        "storedrequest": {
          "id": "main-banner-request"
        }
      }
    },
    "site": {
      "page": "https://example.com",
      "publisher": {
        "id": "1001"
      }
    }
  }'
```

### Test Single Stored Impression

```bash
curl -X POST http://localhost:8080/openrtb2/auction \
  -H "Content-Type: application/json" \
  -d '{
    "id": "test-request",
    "imp": [
      {
        "id": "imp1",
        "ext": {
          "prebid": {
            "storedimp": {
              "id": "banner_300x250"
            }
          }
        }
      }
    ],
    "site": {
      "page": "https://example.com",
      "publisher": {
        "id": "1001"
      }
    }
  }'
```

## Expected Response Structure

When using stored responses (for testing), you'll get responses like:

```json
{
  "id": "test-request",
  "seatbid": [
    {
      "seat": "appnexus",
      "bid": [
        {
          "id": "bid-300x250-1",
          "impid": "imp2",
          "price": 1.25,
          "adm": "<div style=\"width:300px;height:250px;...\">300x250 Banner Ad</div>",
          "w": 300,
          "h": 250,
          "ext": {
            "prebid": {
              "targeting": {
                "hb_bidder": "appnexus",
                "hb_pb": "1.25",
                "hb_size": "300x250"
              }
            }
          }
        }
      ]
    }
  ],
  "cur": "USD"
}
```

## Notes

1. **Account ID**: Make sure the account ID in your request (`publisher.id` or `site.publisher.id`) matches an account in `production/configs/app-settings.yaml` (currently set to `1001`)

2. **Stored Responses**: The stored impressions reference stored responses for testing. In production, remove the `storedbidresponse` references from the stored impression files to use real bidders.

3. **Bidder Configuration**: Each stored impression has bidder configurations (AppNexus, Pubmatic, Rubicon). Update these with your actual bidder credentials.

4. **Testing**: For testing, the stored responses will be returned. For production, remove stored response references to get real bids.

## Production vs Testing

### Testing Mode (Current Setup)
- Stored impressions reference stored responses
- Returns mock bids for testing
- No actual bidder calls made

### Production Mode
To use real bidders, edit the stored impression files and remove:
```json
"storedbidresponse": [
  {
    "bidder": "appnexus",
    "id": "response_300x250"
  }
]
```

Then real bidder calls will be made based on the bidder configurations in each stored impression.

