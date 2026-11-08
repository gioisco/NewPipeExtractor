package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.InitNewPipeTest;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeCommentsExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeCommentsLinkHandlerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.YouTube;
import static org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeCommentsLinkHandlerFactory.NEWEST;
import static org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeCommentsLinkHandlerFactory.TOP;

class YoutubeCommentsSortingTest {
    private static final String VIDEO_ID = "D00Au7k3i6o";
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=" + VIDEO_ID;
    private static final String WATCH_RESPONSE = """
            {"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
              {"itemSectionRenderer":{"targetId":"comments-section","contents":[
                {"continuationItemRenderer":{"continuationEndpoint":{
                  "continuationCommand":{"token":"initial"}}}}
              ]}}
            ]}}}}}
            """;

    private QueueDownloader downloader;

    @BeforeEach
    void setUp() {
        YoutubeTestsUtils.ensureStateless();
        downloader = new QueueDownloader();
        NewPipe.init(downloader);
    }

    @AfterEach
    void tearDown() {
        YoutubeTestsUtils.ensureStateless();
        InitNewPipeTest.initEmpty();
    }

    @Test
    void advertisesSortsAndPreservesTheFilterInTheLinkHandler() throws Exception {
        final YoutubeCommentsLinkHandlerFactory factory =
                YoutubeCommentsLinkHandlerFactory.getInstance();
        assertArrayEquals(new String[]{TOP, NEWEST}, factory.getAvailableSortFilter());
        final var handler = factory.fromQuery(VIDEO_ID, Collections.emptyList(), NEWEST);
        assertEquals(NEWEST, handler.getSortFilter());
        assertEquals(VIDEO_URL, handler.getUrl());
        assertEquals("", factory.fromUrl(VIDEO_URL).getSortFilter());
    }

    @Test
    void defaultOrderDoesNotRequireASortMenuOrAnExtraRequest() throws Exception {
        enqueueInitial(page("default", null, "default-next"));
        final CommentsInfo info = CommentsInfo.getInfo(YouTube, VIDEO_URL);
        assertEquals("default", info.getRelatedItems().get(0).getCommentId());
        assertEquals("", info.getSortFilter());
        assertEquals("default-next", info.getNextPage().getId());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void nullAndEmptyFiltersKeepTheDefaultOrder() throws Exception {
        for (final String sort : new String[]{null, ""}) {
            enqueueInitial(page("default", null, null));
            final CommentsInfo info = CommentsInfo.getInfo(YouTube, VIDEO_URL, sort);
            assertEquals("default", info.getRelatedItems().get(0).getCommentId());
            assertEquals("", info.getSortFilter());
        }
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void newestUsesItsOwnContinuationAndRetainsTheCommentCount() throws Exception {
        enqueueInitial(page("top", menu(TOP), "top-next"));
        downloader.enqueue("continuation", "newest-token", page("newest", null, "newest-next"));
        final CommentsInfo info = CommentsInfo.getInfo(YouTube, VIDEO_URL, NEWEST);
        assertEquals("newest", info.getRelatedItems().get(0).getCommentId());
        assertEquals(NEWEST, info.getSortFilter());
        assertEquals(2, info.getCommentsCount());
        assertEquals("newest-next", info.getNextPage().getId());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());

        downloader.enqueue("continuation", "newest-next", page("older", null, "older-next"));
        final var more = CommentsInfo.getMoreItems(YouTube, info, info.getNextPage());
        assertEquals("older", more.getItems().get(0).getCommentId());
        assertEquals("older-next", more.getNextPage().getId());

        downloader.enqueue("continuation", "older-next", page("oldest", null, null));
        assertFalse(CommentsInfo.getMoreItems(info, more.getNextPage()).hasNextPage());
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void explicitTopReusesThePageWhenTopIsAlreadySelected() throws Exception {
        enqueueInitial(page("top", menu(TOP), null));
        final CommentsInfo info = CommentsInfo.getInfo(YouTube, VIDEO_URL, TOP);
        assertEquals("top", info.getRelatedItems().get(0).getCommentId());
        assertEquals(TOP, info.getSortFilter());
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void explicitTopSwitchesFromADifferentDefaultOrder() throws Exception {
        enqueueInitial(page("newest", menu(NEWEST), null));
        downloader.enqueue("continuation", "top-token", page("top", null, null));
        assertEquals("top", CommentsInfo.getInfo(YouTube, VIDEO_URL, TOP)
                .getRelatedItems().get(0).getCommentId());
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void explicitNewestReusesThePageWhenNewestIsAlreadySelected() throws Exception {
        enqueueInitial(page("newest", menu(NEWEST), null));
        assertEquals("newest", CommentsInfo.getInfo(YouTube, VIDEO_URL, NEWEST)
                .getRelatedItems().get(0).getCommentId());
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void readsTheMenuFromTheWatchCommentsPanelWhenThePageOmitsIt() throws Exception {
        final JsonObject watch = JsonParser.object().from(WATCH_RESPONSE);
        final JsonObject panel = JsonObject.builder()
                .array("engagementPanels")
                    .object().object("engagementPanelSectionListRenderer")
                        .value("panelIdentifier", "engagement-panel-comments-section")
                        .object("header").object("engagementPanelTitleHeaderRenderer")
                            .value("menu", menu(TOP))
                        .end().end()
                    .end().end()
                .end().done();
        watch.put("engagementPanels", panel.getArray("engagementPanels"));
        final JsonObject incompleteMenu = menu(TOP);
        incompleteMenu.getObject("sortFilterSubMenuRenderer").getArray("subMenuItems").remove(1);
        final JsonObject missingTokenMenu = menu(TOP);
        missingTokenMenu.getObject("sortFilterSubMenuRenderer").getArray("subMenuItems").getObject(1)
                .getObject("serviceEndpoint").getObject("continuationCommand").remove("token");
        for (final JsonObject pageMenu : new JsonObject[]{new JsonObject(), incompleteMenu, missingTokenMenu}) {
            downloader.enqueue("videoId", VIDEO_ID, watch);
            downloader.enqueue("continuation", "initial", page("top", pageMenu, null));
            downloader.enqueue("continuation", "newest-token", page("newest", null, null));
            final CommentsInfo info = CommentsInfo.getInfo(YouTube, VIDEO_URL, NEWEST);
            assertEquals("newest", info.getRelatedItems().get(0).getCommentId());
            assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
        }
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void sortSelectionDoesNotDependOnEnglishTitles() throws Exception {
        final JsonObject menu = menu(TOP);
        menu.getObject("sortFilterSubMenuRenderer").getArray("subMenuItems")
                .getObject(0).put("title", "Più pertinenti");
        menu.getObject("sortFilterSubMenuRenderer").getArray("subMenuItems")
                .getObject(1).put("title", "Più recenti");
        enqueueInitial(page("top", menu, null));
        downloader.enqueue("continuation", "newest-token", page("newest", null, null));
        assertEquals("newest", CommentsInfo.getInfo(YouTube, VIDEO_URL, NEWEST)
                .getRelatedItems().get(0).getCommentId());
    }

    @Test
    void missingMenuForAnExplicitSortPropagatesAParsingException() throws Exception {
        for (final String sort : List.of(TOP, NEWEST)) {
            enqueueInitial(page("default", null, null));
            final ParsingException error = assertThrows(ParsingException.class,
                    () -> CommentsInfo.getInfo(YouTube, VIDEO_URL, sort));
            assertTrue(error.getMessage().contains(sort));
        }
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void missingNewestOptionPropagatesAParsingException() throws Exception {
        final JsonObject menu = menu(TOP);
        menu.getObject("sortFilterSubMenuRenderer").getArray("subMenuItems").remove(1);
        enqueueInitial(page("top", menu, null));
        assertThrows(ParsingException.class, () -> CommentsInfo.getInfo(YouTube, VIDEO_URL, NEWEST));
    }

    @Test
    void missingOrEmptySortTokenPropagatesAParsingException() throws Exception {
        for (final String token : new String[]{null, ""}) {
            final JsonObject menu = menu(TOP);
            menu.getObject("sortFilterSubMenuRenderer").getArray("subMenuItems").getObject(1)
                    .getObject("serviceEndpoint").getObject("continuationCommand")
                    .put("token", token);
            enqueueInitial(page("top", menu, null));
            assertThrows(ParsingException.class,
                    () -> CommentsInfo.getInfo(YouTube, VIDEO_URL, NEWEST));
        }
    }

    @Test
    void disabledCommentsRemainAnEmptyResultWithAnExplicitSort() throws Exception {
        downloader.enqueue("videoId", VIDEO_ID, JsonParser.object().from("""
                {"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[]}}}}}
                """));
        final CommentsInfo info = CommentsInfo.getInfo(YouTube, VIDEO_URL, NEWEST);
        assertTrue(info.isCommentsDisabled());
        assertTrue(info.getRelatedItems().isEmpty());
        assertFalse(info.hasNextPage());
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void unsupportedSortIsRejectedBeforeAnyRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> CommentsInfo.getInfo(YouTube, VIDEO_URL, "oldest"));
        assertThrows(IllegalArgumentException.class,
                () -> YoutubeCommentsLinkHandlerFactory.getInstance()
                        .fromQuery(VIDEO_ID, Collections.emptyList(), "oldest"));
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void directExtractorAndReplyContinuationsRemainUsable() throws Exception {
        enqueueInitial(page("top", menu(TOP), null));
        downloader.enqueue("continuation", "newest-token", page("newest", null, null));
        final var handler = YouTube.getCommentsLHFactory()
                .fromQuery(VIDEO_ID, Collections.emptyList(), NEWEST);
        final YoutubeCommentsExtractor extractor =
                (YoutubeCommentsExtractor) YouTube.getCommentsExtractor(handler);
        extractor.fetchPage();
        extractor.fetchPage();
        assertEquals("newest", extractor.getInitialPage().getItems().get(0).getCommentId());

        final JsonObject replyPage = page("reply", null, null);
        final JsonArray items = replyPage.getArray("onResponseReceivedEndpoints").getObject(0)
                .getObject("appendContinuationItemsAction").getArray("continuationItems");
        items.set(0, items.getObject(0).getObject("commentThreadRenderer").getObject("comment"));
        downloader.enqueue("continuation", "replies", replyPage);
        final var replies = CommentsInfo.getMoreItems(YouTube, VIDEO_URL, new Page(VIDEO_URL, "replies"));
        assertEquals("reply", replies.getItems().get(0).getCommentId());
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void requestFailuresPropagateUnchanged() throws Exception {
        enqueueInitial(page("top", menu(TOP), null));
        downloader.enqueue("continuation", "newest-token", page("newest", null, null));
        final IOException failure = new IOException("Simulated request failure");
        downloader.failure = failure;
        assertSame(failure, assertThrows(IOException.class,
                () -> CommentsInfo.getInfo(YouTube, VIDEO_URL, NEWEST)));
    }

    @Test
    void invalidJsonInTheSortedResponsePropagatesAParsingException() throws Exception {
        enqueueInitial(page("top", menu(TOP), null));
        downloader.expected.add(new ExpectedRequest("continuation", "newest-token",
                "Invalid JSON response returned by YouTube instead of the sorted comments"));
        assertThrows(ParsingException.class, () -> CommentsInfo.getInfo(YouTube, VIDEO_URL, NEWEST));
        assertTrue(downloader.expected.isEmpty());
    }

    @Test
    void errorsInIndividualCommentsAreStillCollected() throws Exception {
        final JsonObject sorted = page("newest", null, null);
        sorted.getArray("onResponseReceivedEndpoints").getObject(0)
                .getObject("appendContinuationItemsAction").getArray("continuationItems")
                .getObject(0).getObject("commentThreadRenderer").getObject("comment")
                .getObject("commentRenderer").put("publishedTimeText", "invalid");
        enqueueInitial(page("top", menu(TOP), null));
        downloader.enqueue("continuation", "newest-token", sorted);
        final CommentsInfo info = CommentsInfo.getInfo(YouTube, VIDEO_URL, NEWEST);
        assertEquals("newest", info.getRelatedItems().get(0).getCommentId());
        assertFalse(info.getErrors().isEmpty());
    }

    private void enqueueInitial(final JsonObject page) throws Exception {
        final JsonArray endpoints = page.getArray("onResponseReceivedEndpoints");
        if (!endpoints.getObject(0).has("reloadContinuationItemsCommand")) {
            endpoints.add(0, JsonObject.builder().object("reloadContinuationItemsCommand")
                    .array("continuationItems").object().object("commentsHeaderRenderer")
                        .object("countText").value("simpleText", "2").end()
                    .end().end().end().end().done());
        }
        downloader.enqueue("videoId", VIDEO_ID, JsonParser.object().from(WATCH_RESPONSE));
        downloader.enqueue("continuation", "initial", page);
    }

    private static JsonObject menu(final String selected) {
        return JsonObject.builder()
                .object("sortFilterSubMenuRenderer").array("subMenuItems")
                    .object().value("title", "Top comments").value("selected", TOP.equals(selected))
                        .object("serviceEndpoint").object("continuationCommand")
                            .value("token", "top-token").end().end()
                    .end()
                    .object().value("title", "Newest first").value("selected", NEWEST.equals(selected))
                        .object("serviceEndpoint").object("continuationCommand")
                            .value("token", "newest-token").end().end()
                    .end()
                .end().end().done();
    }

    private static JsonObject page(final String id, final JsonObject menu, final String next)
            throws Exception {
        final JsonObject thread = JsonParser.object().from("""
                {"commentThreadRenderer":{"comment":{"commentRenderer":{
                  "authorText":{"simpleText":"Author"},
                  "authorEndpoint":{"browseEndpoint":{"browseId":"UCauthor"}},
                  "authorThumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/avatar=s88",
                    "width":88,"height":88}]},
                  "contentText":{"simpleText":"A comment"},
                  "publishedTimeText":{"runs":[{"text":"1 day ago"}]},
                  "replyCount":0
                }}}}
                """);
        thread.getObject("commentThreadRenderer").getObject("comment")
                .getObject("commentRenderer").put("commentId", id);
        final JsonArray endpoints = new JsonArray();
        if (menu != null) {
            endpoints.add(JsonObject.builder().object("reloadContinuationItemsCommand")
                    .array("continuationItems").object().object("commentsHeaderRenderer")
                        .object("countText").value("simpleText", "2").end()
                        .value("sortMenu", menu)
                    .end().end().end().end().done());
        }
        final JsonArray items = new JsonArray();
        items.add(thread);
        if (next != null) {
            items.add(JsonObject.builder().object("continuationItemRenderer")
                    .object("continuationEndpoint").object("continuationCommand")
                    .value("token", next).end().end().end().done());
        }
        endpoints.add(JsonObject.builder().object("appendContinuationItemsAction")
                .value("continuationItems", items).end().done());
        return JsonObject.builder().value("onResponseReceivedEndpoints", endpoints).done();
    }

    private static final class QueueDownloader extends Downloader {
        private final Deque<ExpectedRequest> expected = new ArrayDeque<>();
        private IOException failure;

        void enqueue(final String key, final String value, final JsonObject response) {
            expected.add(new ExpectedRequest(key, value, JsonWriter.string(response)));
        }

        @Nonnull
        @Override
        public Response execute(@Nonnull final Request request) throws IOException {
            if (request.url().equals("https://www.youtube.com/sw.js_data")) {
                final JsonArray client = new JsonArray(Collections.nCopies(16, null));
                client.add("2.20261108.00.00");
                final JsonArray sw = new JsonArray();
                sw.add(new JsonArray(java.util.Arrays.asList(null, null,
                        new JsonArray(List.of(new JsonArray(List.of(client)))))));
                return response(request, JsonWriter.string(sw));
            }
            assertTrue(request.url().startsWith("https://www.youtube.com/youtubei/v1/next?"),
                    request.url());
            assertEquals("POST", request.httpMethod());
            final ExpectedRequest next = expected.poll();
            assertNotNull(next, "Unexpected request to " + request.url());
            try {
                final JsonObject body = JsonParser.object().from(
                        new String(request.dataToSend(), StandardCharsets.UTF_8));
                assertEquals(next.value, body.getString(next.key));
            } catch (final com.grack.nanojson.JsonParserException e) {
                throw new AssertionError(e);
            }
            if (failure != null && expected.isEmpty()) {
                throw failure;
            }
            return response(request, next.response);
        }

        private Response response(final Request request, final String body) {
            return new Response(200, "OK", Collections.emptyMap(), body, request.url());
        }
    }

    private static final class ExpectedRequest {
        private final String key;
        private final String value;
        private final String response;

        ExpectedRequest(final String key, final String value, final String response) {
            this.key = key;
            this.value = value;
            this.response = response;
        }
    }
}
