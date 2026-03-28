# BMS Ingestion Protocol (Current Implementation Baseline)

This document defines the line protocol accepted by the Java ingestion service.

## 1. Transport

- Sender: Raspberry Pi bridge process
- Receiver: Java service endpoint POST /api/ingest
- Content type: text/plain
- Body can contain one or multiple lines

## 2. Supported Line Types

### 2.1 BMS line

Preferred 4-module format:

BMS,module_id,voltage_v,current_a,soc_raw,status_code,cell_1_mv,cell_2_mv,...

Legacy single-module format:

BMS,voltage_v,current_a,soc_raw,status_code,cell_1_mv,cell_2_mv,...

Notes:

- module_id range: 1..4
- soc_raw conversion: if soc_raw > 1000 then soc_percent = soc_raw / 1000000
- cell values are stored in millivolts

Example:

BMS,2,15.500,0.000,85000000,155,4000,3200,3900,3700

### 2.2 EVENT line

Preferred format:

EVENT,module_id,event_code,severity,message

Fallback format:

EVENT,event_code,severity,message

Severity values accepted in parser:

- INFO
- WARN
- ERROR

Example:

EVENT,3,49,WARN,Cell imbalance detected

## 3. Validation Rules

- Empty lines are ignored
- Unknown prefixes are rejected
- Invalid numeric fields are defaulted to safe values in current implementation
- module_id outside 1..4 defaults to 1

## 4. Settings Phase (Planned)

The next phase will map command-level read/write operations using:

- TinyBMS_Communication_Protocols_Rev_D.pdf

Planned first scope:

- Cell Settings
- Statistics

These require full command table extraction (frame IDs, payload format, checksum, limits).
