# Stored Impressions Directory

This directory contains stored impression (imp) objects that can be referenced by ID.

## Purpose
Stored impressions allow you to pre-define impression configurations (ad formats, sizes, media types, etc.) that can be reused across multiple bid requests. This is useful for:
- Standardizing ad unit configurations
- Simplifying bid request creation
- Managing impression templates separately from full bid requests

## File Format
- Files should be valid OpenRTB impression (imp) objects
- Filename typically corresponds to the stored impression ID (e.g., `banner_300x250.json` would be referenced as stored impression ID `banner_300x250`)
- The JSON should contain impression configuration including banner/video/native objects, bidfloor, etc.

## Usage
When making a bid request, you can reference a stored impression by including:
```json
{
  "imp": [{
    "id": "imp1",
    "ext": {
      "prebid": {
        "storedimp": {
          "id": "banner_300x250"
        }
      }
    }
  }]
}
```

The server will load and merge the stored impression from this directory.
