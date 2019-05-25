package com.faforever.client.player;

import com.faforever.client.avatar.Avatar;
import com.faforever.client.avatar.event.AvatarChangedEvent;
import com.faforever.client.chat.ChatChannelUser;
import com.faforever.client.chat.ChatMessageEvent;
import com.faforever.client.chat.ChatUserCreatedEvent;
import com.faforever.client.fx.JavaFxUtil;
import com.faforever.client.game.Game;
import com.faforever.client.game.GameAddedEvent;
import com.faforever.client.game.GameRemovedEvent;
import com.faforever.client.game.GameState;
import com.faforever.client.game.GameUpdatedEvent;
import com.faforever.client.player.SocialRelationsServerMessage.SocialRelation;
import com.faforever.client.player.SocialRelationsServerMessage.SocialRelation.RelationType;
import com.faforever.client.remote.FafService;
import com.faforever.client.user.LoginSuccessEvent;
import com.faforever.client.user.UserService;
import com.faforever.client.util.Assert;
import com.google.common.eventbus.Subscribe;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.faforever.client.player.SocialStatus.FOE;
import static com.faforever.client.player.SocialStatus.FRIEND;
import static com.faforever.client.player.SocialStatus.OTHER;
import static com.faforever.client.player.SocialStatus.SELF;

@Service
public class PlayerService {

  private final ObservableMap<String, Player> playersByName;
  private final ObservableMap<Integer, Player> playersById;
  private final List<Integer> foeList;
  private final List<Integer> friendList;
  private final ObjectProperty<Player> currentPlayer;

  private final FafService fafService;
  private final UserService userService;
  private final ApplicationEventPublisher eventPublisher;
  private final HashMap<Integer, List<Player>> playersByGame;

  public PlayerService(FafService fafService, UserService userService, ApplicationEventPublisher eventPublisher) {
    this.fafService = fafService;
    this.userService = userService;
    this.eventPublisher = eventPublisher;

    playersByName = FXCollections.observableMap(new ConcurrentHashMap<>());
    playersById = FXCollections.observableHashMap();
    friendList = new ArrayList<>();
    foeList = new ArrayList<>();
    currentPlayer = new SimpleObjectProperty<>();
    playersByGame = new HashMap<>();
  }

  @EventListener
  public void onGameAdded(GameAddedEvent event) {
    updateGameForPlayersInGame(event.getGame());
  }

  @EventListener
  public void onGameUpdated(GameUpdatedEvent event) {
    updateGameForPlayersInGame(event.getGame());
  }

  @EventListener
  public void onGameRemoved(GameRemovedEvent event) {
    Game game = event.getGame();
    ObservableMap<Integer, List<Player>> teams = game.getTeams();
    synchronized (teams) {
      teams.forEach((team, players) -> updateGamePlayers(players, null));
    }
  }

  @EventListener
  public void onPlayerOfflineMessage(PlayerOfflineMessage message) {
    playersById.remove(message.getPlayerId());
  }

  private void updateGameForPlayersInGame(Game game) {
    ObservableMap<Integer, List<Player>> teams = game.getTeams();
    synchronized (teams) {
      teams.forEach((team, players) -> updateGamePlayers(players, game));
    }
  }

  @EventListener
  public void onLoginSuccess(LoginSuccessEvent event) {
    Player player = createAndGetPlayerForUsername(event.getDisplayName());
    player.setId(event.getUserId());
    currentPlayer.set(player);
    player.setIdleSince(Instant.now());
  }

  @Subscribe
  public void onAvatarChanged(AvatarChangedEvent event) {
    Player player = getCurrentPlayer().orElseThrow(() -> new IllegalStateException("Player has not been set"));

    Avatar avatar = event.getAvatar();
    if (avatar == null) {
      player.setAvatarTooltip(null);
      player.setAvatarUrl(null);
    } else {
      player.setAvatarTooltip(avatar.getDescription());
      player.setAvatarUrl(avatar.getUrl());
    }
  }

  @EventListener
  public void onChatMessage(ChatMessageEvent event) {
    getPlayerForUsername(event.getMessage().getUsername()).ifPresent(this::resetIdleTime);
  }

  private void resetIdleTime(Player playerForUsername) {
    Optional.ofNullable(playerForUsername).ifPresent(player -> player.setIdleSince(Instant.now()));
  }

  private void updateGamePlayers(List<Player> players, Game game) {
    players.forEach(player -> {
      resetIdleTime(player);
      updateGameToPlayer(game, player);
      if (game == null || game.getState() == GameState.CLOSED) {
        player.setGame(null);
      } else {
        player.setGame(game);
        if ((player.getGame() == null || !player.getGame().equals(game)) && player.getSocialStatus() == FRIEND && game.getState() == GameState.OPEN) {
          eventPublisher.publishEvent(new FriendJoinedGameEvent(player, game));
        }
      }
    });

    if (game != null && game.getState() != GameState.CLOSED && playersByGame.get(game.getId()) != null) {
      playersByGame.get(game.getId()).forEach(player -> {
        boolean stillInGame = game.getTeams().entrySet().stream()
          .anyMatch(entry -> entry.getValue().stream()
            .map(Player::getId)
            .anyMatch(id -> player.getId() == id));
        if (!stillInGame) {
          player.setGame(null);
        }
      });
    }
  }

  private void updateGameToPlayer(Game game, Player player) {
    if (game == null) {
      return;
    }

    if (game.getState() == GameState.CLOSED) {
      playersByGame.remove(game.getId());
      return;
    }

    if (!playersByGame.containsKey(game.getId())) {
      playersByGame.put(game.getId(), new ArrayList<>());
    }

    playersByGame.get(game.getId()).add(player);
  }


  public boolean isOnline(int playerId) {
    return playersById.containsKey(playerId);
  }

  /**
   * Returns the player for the specified display name. Returns null if no such player is known.
   */
  public Optional<Player> getPlayerForUsername(@NotNull String displayName) {
    return Optional.ofNullable(playersByName.get(displayName));
  }

  public Optional<Player> getPlayerById(int id) {
    return Optional.ofNullable(playersById.get(id));
  }

  /**
   * Gets a player for the given displayName. A new player is created and registered if it does not yet exist.
   */
  Player createAndGetPlayerForUsername(@NotNull String username) {
    Assert.checkNullArgument(username, "displayName must not be null");

    synchronized (playersByName) {
      if (!playersByName.containsKey(username)) {
        Player player = new Player(username);
        JavaFxUtil.addListener(player.idProperty(), (observable, oldValue, newValue) -> {
          synchronized (playersById) {
            playersById.remove(oldValue.intValue());
            playersById.put(newValue.intValue(), player);
          }
        });
        playersByName.put(username, player);
      }
    }

    return playersByName.get(username);
  }

  public Set<String> getPlayerNames() {
    return new HashSet<>(playersByName.keySet());
  }

  public void addFriend(Player player) {
    playersByName.get(player.getDisplayName()).setSocialStatus(FRIEND);
    friendList.add(player.getId());
    foeList.remove((Integer) player.getId());

    fafService.addFriend(player);
  }

  public void removeFriend(Player player) {
    playersByName.get(player.getDisplayName()).setSocialStatus(OTHER);
    friendList.remove((Integer) player.getId());

    fafService.removeFriend(player);
  }

  public void addFoe(Player player) {
    playersByName.get(player.getDisplayName()).setSocialStatus(FOE);
    foeList.add(player.getId());
    friendList.remove((Integer) player.getId());

    fafService.addFoe(player);
  }

  public void removeFoe(Player player) {
    playersByName.get(player.getDisplayName()).setSocialStatus(OTHER);
    foeList.remove((Integer) player.getId());

    fafService.removeFoe(player);
  }

  public Optional<Player> getCurrentPlayer() {
    return Optional.ofNullable(currentPlayer.get());
  }

  public ReadOnlyObjectProperty<Player> currentPlayerProperty() {
    return currentPlayer;
  }

  public CompletableFuture<List<Player>> getPlayersByIds(Collection<Integer> playerIds) {
    return fafService.getPlayersByIds(playerIds);
  }

  @Subscribe
  public void onChatUserCreated(ChatUserCreatedEvent event) {
    ChatChannelUser chatChannelUser = event.getChatChannelUser();
    Optional.ofNullable(playersByName.get(chatChannelUser.getUsername()))
      .ifPresent(player -> Platform.runLater(() -> {
        chatChannelUser.setPlayer(player);
        player.getChatChannelUsers().add(chatChannelUser);
      }));
  }

  @EventListener
  public void onPlayersInfo(PlayersServerMessage playersMessage) {
    playersMessage.getPlayers().forEach(this::onPlayerInfo);
  }

  @EventListener
  public void onFoeList(SocialRelationsServerMessage socialMessage) {
    List<Integer> friends = socialMessage.getSocialRelations().stream()
      .filter(socialRelation -> socialRelation.getType() == RelationType.FRIEND)
      .map(SocialRelation::getPlayerId)
      .collect(Collectors.toList());

    List<Integer> foes = socialMessage.getSocialRelations().stream()
      .filter(socialRelation -> socialRelation.getType() == RelationType.FOE)
      .map(SocialRelation::getPlayerId)
      .collect(Collectors.toList());

    onFoeList(foes);
    onFriendList(friends);
  }

  private void onFoeList(List<Integer> foes) {
    updateSocialList(foeList, foes, FOE);
  }

  private void onFriendList(List<Integer> friends) {
    updateSocialList(friendList, friends, FRIEND);
  }

  private void updateSocialList(List<Integer> socialList, List<Integer> newValues, SocialStatus socialStatus) {
    socialList.clear();
    socialList.addAll(newValues);

    synchronized (playersById) {
      for (Integer userId : socialList) {
        Player player = playersById.get(userId);
        if (player != null) {
          player.setSocialStatus(socialStatus);
        }
      }
    }
  }

  private void onPlayerInfo(PlayerServerMessage playerInfo) {
    Player player;
    if (Objects.equals(playerInfo.getId(), userService.getUserId())) {
      player = getCurrentPlayer().orElseThrow(() -> new IllegalStateException("Player has not been set"));
      updatePlayer(player, playerInfo);
      player.setSocialStatus(SELF);
    } else {
      player = createAndGetPlayerForUsername(playerInfo.getDisplayName());

      if (friendList.contains(player.getId())) {
        player.setSocialStatus(FRIEND);
      } else if (foeList.contains(player.getId())) {
        player.setSocialStatus(FOE);
      } else {
        player.setSocialStatus(OTHER);
      }

      updatePlayer(player, playerInfo);

      eventPublisher.publishEvent(new PlayerOnlineEvent(player));
    }

    eventPublisher.publishEvent(new PlayerUpdatedEvent(player));
  }

  // TODO can we use mapstruct for this?
  private void updatePlayer(Player player, PlayerServerMessage message) {
    player.setId(message.getId());
    player.setClanTag(message.getClanTag());
    player.setCountry(message.getCountry());

    player.setNumberOfGames(message.getNumberOfGames());
    if (message.getAvatar() != null) {
      player.setAvatarUrl(message.getAvatar().getUrl());
      player.setAvatarTooltip(message.getAvatar().getDescription());
    }
  }
}