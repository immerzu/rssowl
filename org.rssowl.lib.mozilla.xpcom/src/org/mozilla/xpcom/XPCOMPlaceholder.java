package org.mozilla.xpcom;

/**
 * Minimal package provider for the legacy SWT dynamic import used during x64
 * PDE resolution. RSSOwl disables the Mozilla browser path on Windows x64.
 */
public final class XPCOMPlaceholder {

  private XPCOMPlaceholder() {
    // Utility class.
  }
}
