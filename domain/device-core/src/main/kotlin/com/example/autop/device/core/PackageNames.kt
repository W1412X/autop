/**
 * Android 包名格式（tool_optimize 02 §13.3）：RestartAppTool 与 AdbShellInput 共用。
 */
package com.example.autop.device.core

/** 包名正则。 */
public object PackageNames {
    public val PATTERN: Regex = Regex("[A-Za-z0-9._]+")
}
