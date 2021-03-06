package com.faforever.client.remote.domain;

import java.util.HashMap;
import java.util.Map;

public enum GameAccess {
  PUBLIC("public"),
  PASSWORD("password");

  private static final Map<String, GameAccess> fromString;

  static {
    fromString = new HashMap<>(values().length, 1);
    for (GameAccess gameAccess : values()) {
      fromString.put(gameAccess.getString(), gameAccess);
    }
  }

  private final String string;

  GameAccess(String string) {
    this.string = string;
  }

  public String getString() {
    return string;
  }

  public static GameAccess fromString(String string) {
    return fromString.get(string);
  }
}
