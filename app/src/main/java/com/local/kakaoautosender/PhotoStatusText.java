package com.local.kakaoautosender;

import java.util.LinkedHashSet;

/** Present shared diagnostic outcomes once while retaining distinct capability/schedule details. */
final class PhotoStatusText {
  private PhotoStatusText() {}

  static String combine(String... sections) {
    LinkedHashSet<String> lines = new LinkedHashSet<>();
    for (String section : sections) {
      if (section == null) continue;
      for (String line : section.split("\\r?\\n")) {
        if (!line.trim().isEmpty()) lines.add(line.trim());
      }
    }
    return String.join("\n", lines);
  }
}
