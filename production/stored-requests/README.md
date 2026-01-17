# Stored Requests Directory

This directory contains stored OpenRTB bid request templates.

## Purpose
Stored requests allow you to pre-define bid request structures that can be referenced by ID. This is useful for:
- Standardizing bid request formats across multiple publishers
- Simplifying client-side integration (clients only need to send a stored request ID instead of full bid request JSON)
- Managing bid request templates centrally

## File Format
- Files should be valid OpenRTB 2.x JSON bid request objects
- Filename typically corresponds to the stored request ID (e.g., `12345.json` would be referenced as stored request ID `12345`)
- The JSON should contain a complete bid request structure including site/app, device, user, and imp objects

## Usage
When making a bid request, you can reference a stored request by including:
```json
{
  "ext": {
    "prebid": {
      "storedrequest": {
        "id": "12345"
      }
    }
  }
}
```

The server will load and merge the stored request from this directory.
