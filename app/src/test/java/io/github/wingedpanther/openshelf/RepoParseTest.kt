package io.github.wingedpanther.openshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepoParseTest {
    @Test fun plainOwnerRepo() {
        assertEquals("owner/repo", ManualLinks.parseRepo("owner/repo"))
        assertEquals("my-org/my.repo_1", ManualLinks.parseRepo("my-org/my.repo_1"))
    }

    @Test fun fullUrls() {
        assertEquals("owner/repo", ManualLinks.parseRepo("https://github.com/owner/repo"))
        assertEquals("owner/repo", ManualLinks.parseRepo("http://github.com/owner/repo"))
        assertEquals("owner/repo", ManualLinks.parseRepo("github.com/owner/repo"))
        assertEquals("owner/repo", ManualLinks.parseRepo("https://www.github.com/owner/repo"))
    }

    @Test fun trailingSlashAndGit() {
        assertEquals("owner/repo", ManualLinks.parseRepo("https://github.com/owner/repo/"))
        assertEquals("owner/repo", ManualLinks.parseRepo("https://github.com/owner/repo.git"))
        assertEquals("owner/repo", ManualLinks.parseRepo("owner/repo.git"))
    }

    @Test fun extraPathQueryAndFragment() {
        assertEquals("owner/repo", ManualLinks.parseRepo("https://github.com/owner/repo/releases/latest"))
        assertEquals("owner/repo", ManualLinks.parseRepo("https://github.com/owner/repo/releases/tag/v1.0"))
        assertEquals("owner/repo", ManualLinks.parseRepo("https://github.com/owner/repo?tab=readme"))
        assertEquals("owner/repo", ManualLinks.parseRepo("https://github.com/owner/repo#readme"))
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        assertEquals("owner/repo", ManualLinks.parseRepo("  owner/repo  "))
    }

    @Test fun blanksAndJunkReturnNull() {
        assertNull(ManualLinks.parseRepo(""))
        assertNull(ManualLinks.parseRepo("   "))
        assertNull(ManualLinks.parseRepo("repo"))
        assertNull(ManualLinks.parseRepo("owner/"))
        assertNull(ManualLinks.parseRepo("/repo"))
        assertNull(ManualLinks.parseRepo("a/b/c"))
        assertNull(ManualLinks.parseRepo("hello world"))
        assertNull(ManualLinks.parseRepo("https://example.com/owner/repo"))
        assertNull(ManualLinks.parseRepo("https://github.com/owner"))
    }

    @Test fun directRepoOfNeedsASlashAndNoSpaces() {
        assertEquals("owner/repo", Direct.repoOf("owner/repo"))
        assertEquals("owner/repo", Direct.repoOf("  https://github.com/owner/repo  "))
        assertNull(Direct.repoOf("repo"))
        assertNull(Direct.repoOf("owner/ repo"))
        assertNull(Direct.repoOf("some app/name"))
        assertNull(Direct.repoOf(""))
    }
}
