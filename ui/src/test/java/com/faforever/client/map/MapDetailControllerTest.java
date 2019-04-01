package com.faforever.client.map;

import com.faforever.client.i18n.I18n;
import com.faforever.client.notification.NotificationService;
import com.faforever.client.player.Player;
import com.faforever.client.player.PlayerService;
import com.faforever.client.reporting.ReportingService;
import com.faforever.client.review.Review;
import com.faforever.client.review.ReviewService;
import com.faforever.client.test.AbstractPlainJavaFxTest;
import com.faforever.client.util.TimeService;
import com.faforever.client.vault.review.ReviewController;
import com.faforever.client.vault.review.ReviewsController;
import com.faforever.client.vault.review.StarController;
import com.faforever.client.vault.review.StarsController;
import com.google.common.eventbus.EventBus;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.testfx.util.WaitForAsyncUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MapDetailControllerTest extends AbstractPlainJavaFxTest {

  @Mock
  private MapService mapService;
  @Mock
  private NotificationService notificationService;
  @Mock
  private ReportingService reportingService;
  @Mock
  private TimeService timeService;
  @Mock
  private PlayerService playerService;
  @Mock
  private ReviewService reviewService;
  @Mock
  private I18n i18n;
  @Mock
  private ReviewsController reviewsController;
  @Mock
  private ReviewController reviewController;
  @Mock
  private StarsController starsController;
  @Mock
  private StarController starController;
  @Mock
  private EventBus eventBus;
  @Mock
  private MapPreviewService mapPreviewService;

  private MapDetailController instance;

  @Before
  public void setUp() throws Exception {
    when(mapService.downloadAndInstallMap(any(), any(DoubleProperty.class), any(StringProperty.class))).thenReturn(CompletableFuture.runAsync(() -> {
    }));
    when(i18n.get(anyString(), anyInt(), anyInt())).thenReturn("map size");
    when(mapService.hasPlayedMap(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(true));
    when(mapService.getFileSize(any(URL.class))).thenReturn(CompletableFuture.completedFuture(12));
    when(mapService.getInstalledMaps()).thenReturn(FXCollections.observableArrayList());
    instance = new MapDetailController(mapService, mapPreviewService, notificationService, i18n, reportingService, timeService, playerService, reviewService, eventBus);

    loadFxml("theme/vault/map/map_detail.fxml", param -> {
      if (param == ReviewsController.class) {
        return reviewsController;
      }
      if (param == ReviewController.class) {
        return reviewController;
      }
      if (param == StarsController.class) {
        return starsController;
      }
      if (param == StarController.class) {
        return starController;
      }
      return instance;
    });
  }

  @Test
  public void onInstallButtonClicked() {
    instance.onInstallButtonClicked();
    WaitForAsyncUtils.waitForFxEvents();
    assertThat(instance.uninstallButton.isVisible(), is(true));
    assertThat(instance.installButton.isVisible(), is(false));
  }

  @Test
  public void testAuthorControls() throws MalformedURLException {
    when(playerService.getCurrentPlayer()).then(invocation -> {
      Player player = mock(Player.class);
      when(player.getDisplayName()).thenReturn("axel12");
      return Optional.of(player);
    });
    FaMap faMap = new FaMap();
    faMap.setAuthor("axel12");
    faMap.setRanked(true);
    faMap.setHidden(false);
    faMap.setId("23");
    faMap.setSize(MapSize.valueOf(1, 1));
    faMap.setDownloadUrl(new URL("http://google.com"));

    when(reviewService.findOwnMapReview(faMap.getId())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

    instance.setMap(faMap);

    assertThat(instance.hideRow.getPrefHeight(), not(is(0)));
    assertThat(instance.unrankButton.isVisible(), is(true));
  }

  @Test
  public void testAuthorControlsHiddenWhenPlayerNotAuthor() throws MalformedURLException {
    when(playerService.getCurrentPlayer()).then(invocation -> {
      Player player = mock(Player.class);
      return Optional.of(player);
    });
    FaMap faMap = new FaMap();
    faMap.setAuthor("axel12");
    faMap.setRanked(true);
    faMap.setHidden(false);
    faMap.setId("23");
    faMap.setSize(MapSize.valueOf(1, 1));
    faMap.setDownloadUrl(new URL("http://google.com"));

    when(reviewService.findOwnMapReview(faMap.getId())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

    instance.setMap(faMap);

    assertThat(instance.hideRow.getPrefHeight(), is(0.0));
    assertThat(instance.unrankButton.isVisible(), is(false));
  }
}