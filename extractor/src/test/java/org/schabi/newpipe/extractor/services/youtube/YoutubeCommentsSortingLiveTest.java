package org.schabi.newpipe.extractor.services.youtube;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.YouTube;
import static org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeCommentsLinkHandlerFactory.NEWEST;
import static org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeCommentsLinkHandlerFactory.TOP;

/**
 * Runs only with {@code -Ddownloader=REAL}; ordinary mock runs need no live fixtures.
 */
@EnabledIfSystemProperty(named = "downloader", matches = "(?i)REAL")
public class YoutubeCommentsSortingLiveTest implements InitYoutubeTest {

    @ParameterizedTest
    @CsvSource({"D00Au7k3i6o, 1", "dQw4w9WgXcQ, 3"})
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void fetchesBothOrdersAndAvailableFollowingPages(final String videoId, final int minimumPages)
            throws Exception {
        final String url = "https://www.youtube.com/watch?v=" + videoId;
        for (final String sort : List.of("", TOP, NEWEST)) {
            final String context = videoId + " sort=" + (sort.isEmpty() ? "default" : sort);
            final CommentsInfo info = sort.isEmpty()
                    ? CommentsInfo.getInfo(YouTube, url)
                    : CommentsInfo.getInfo(YouTube, url, sort);
            assertNotNull(info, context);
            assertFalse(info.isCommentsDisabled(), context);
            assertEquals(sort, info.getSortFilter(), context);
            assertTrue(info.getCommentsCount() > 0, context);
            assertTrue(info.getErrors().isEmpty(), () -> context + ": " + info.getErrors());

            final Set<String> seenIds = new HashSet<>();
            verifyPage(info.getRelatedItems(), seenIds, context + " page=1");
            Page next = info.getNextPage();
            int pagesLoaded = 1;
            for (int pageNumber = 2; pageNumber <= 3 && Page.isValid(next); pageNumber++) {
                final String previousToken = next.getId();
                final var page = CommentsInfo.getMoreItems(YouTube, info, next);
                assertTrue(page.getErrors().isEmpty(), () -> context + ": " + page.getErrors());
                verifyPage(page.getItems(), seenIds, context + " page=" + pageNumber);
                next = page.getNextPage();
                if (Page.isValid(next)) {
                    assertNotEquals(previousToken, next.getId(), context + " repeats a continuation");
                }
                pagesLoaded++;
            }
            assertTrue(pagesLoaded >= minimumPages,
                    context + " loaded " + pagesLoaded + " pages; expected at least " + minimumPages);
            System.out.println(context + ": loaded " + pagesLoaded + " pages, "
                    + seenIds.size() + " distinct comments");
        }
    }

    private void verifyPage(final List<CommentsInfoItem> comments,
                            final Set<String> seenIds, final String context) {
        assertFalse(comments.isEmpty(), context);
        final int previousCount = seenIds.size();
        for (final CommentsInfoItem comment : comments) {
            assertNotNull(comment.getCommentId(), context);
            assertFalse(comment.getCommentId().isBlank(), context);
            assertNotNull(comment.getTextualUploadDate(), context);
            assertFalse(comment.getTextualUploadDate().isBlank(), context);
            seenIds.add(comment.getCommentId());
        }
        assertTrue(seenIds.size() > previousCount, context + " contains no new comment IDs");
        System.out.println(context + ": " + comments.size() + " comments");
        comments.stream().limit(3).forEach(comment -> System.out.println(
                "  " + comment.getCommentId() + " | " + comment.getTextualUploadDate()));
    }
}
