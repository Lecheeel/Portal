package com.system.location.service.core.privacy

/** Redacts common environment identifiers and credentials from diagnostic text. */
object DiagnosticExportRedactor {
    private val jsonSensitiveField = Regex(
        """(?i)([\"'](?:api[\s_-]?key|private[\s_-]?key|client[\s_-]?secret|access[\s_-]?token|refresh[\s_-]?token|secret|key|token|authorization|password|ssid|bssid|mac|address|name|devicename|identifier|scan.?record(?:hex)?|imei|imsi|cell(?:id|identity|cid|nci|tac|lac)|mcc|mnc|lac|tac|cid|nci|arfcn|earfcn|sid|nid|bid|uuid|major|minor|beaconid|latitude|longitude|lat|lon)[\"']\s*:\s*)(?:\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|[^,}\]\s]+)""",
    )
    private val legacyScenarioName = Regex(
        """最近配置\s*[:：=＝]\s*.*?(?=\s+rev\s*[:=＝]|[;\n]|$)""",
    )
    private val authorizationHeader = Regex(
        """(?i)\b(authorization)\s*[:：=＝]\s*(?:(?:Bearer|Basic)\s+)?[^\s,;]+""",
    )
    private val urlUserInfo = Regex(
        """(?i)(\bhttps?://)[^\s/@]+@""",
    )
    private val ssidValue = Regex(
        """(?i)\bssid\s*[:：=＝]\s*(?:"[^"]*"|'[^']*'|.*?)(?=\s+(?:bssid|stage|result|reason|suggestion|rssi|frequency|capabilities|cell(?:id|cid|nci)|mcc|mnc|lac|tac|cid|nci)\s*[:：=＝]|[;\n]|$)""",
    )
    private val labeledValue = Regex(
        """(?i)\b(api[\s_-]?key|private[\s_-]?key|client[\s_-]?secret|access[\s_-]?token|refresh[\s_-]?token|secret|key|token|authorization|password|bssid|mac|address|deviceName|identifier|scan.?record(?:hex)?|imei|imsi|cell(?:id|identity|cid|nci|tac|lac)|mcc|mnc|lac|tac|cid|nci|arfcn|earfcn|sid|nid|bid|uuid|major|minor|beaconid)\b\s*[:：=＝]\s*[^\s,;]+""",
    )
    private val sensitiveTextValue = Regex(
        """(?i)\b(name|deviceName|address|identifier|scan.?record(?:hex)?)\s*[:：=＝]\s*(?:"[^"]*"|'[^']*'|.*?)(?=\s+\w+\s*[:：=＝]|[,;\n)]|$)""",
    )
    private val macAddress = Regex("""(?i)\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b|\b(?:[0-9a-f]{4}\.){2}[0-9a-f]{4}\b""")
    private val coordinate = Regex(
        """(?i)\b(latitude|longitude|lat|lon)\s*[:：=＝]\s*-?\d+(?:\.\d+)?|\b(WGS84)\s*[:：=＝]\s*-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?""",
    )
    private val androidLocationCoordinate = Regex(
        """(?i)\bLocation\[((?:[^\]\s]+\s+)?)-?\d{1,2}(?:\.\d+)?\s*,\s*-?\d{1,3}(?:\.\d+)?""",
    )

    fun redact(report: String): String = report
        .replace(jsonSensitiveField) { "${it.groupValues[1]}\"<redacted>\"" }
        .replace(legacyScenarioName, "最近配置=<redacted>")
        .replace(authorizationHeader) { "${it.groupValues[1]}=<redacted>" }
        .replace(urlUserInfo) { "${it.groupValues[1]}<redacted>@" }
        .replace(ssidValue) { "ssid=<redacted>" }
        .replace(sensitiveTextValue) { "${it.groupValues[1]}=<redacted>" }
        .replace(labeledValue) { "${it.groupValues[1]}=<redacted>" }
        .replace(macAddress, "<redacted-mac>")
        .replace(coordinate) { match -> "${match.groupValues[1].ifBlank { match.groupValues[2] }}=<redacted>" }
        .replace(androidLocationCoordinate) { "Location[${it.groupValues[1]}<redacted>" }
}
