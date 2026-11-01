package su.plo.voice.platform.forge.server.groups.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.junit.Test;

public class CreateFlagsTest {
    private static CreateFlags parse(String line) {
        return CreateFlags.parse(("create " + line).trim().split(" "));
    }

    @Test
    public void flagOrderDoesNotMatter() {
        CreateFlags a = parse("name:Alpha password:pw");
        CreateFlags b = parse("password:pw name:Alpha");
        assertEquals("Alpha", a.name());
        assertEquals("Alpha", b.name());
        assertEquals("pw", a.password());
        assertEquals("pw", b.password());
    }

    @Test
    public void nameKeepsAllWordsUntilNextFlag() {
        CreateFlags flags = parse("name:My Cool Group password:pw");
        assertEquals("My Cool Group", flags.name());
        assertEquals("pw", flags.password());
    }

    @Test
    public void spaceAfterColonIsTrimmed() {
        assertEquals("My Group", parse("name: My Group").name());
    }

    @Test
    public void multiplePermissionsSplitOnCommasAndAreTrimmed() {
        CreateFlags flags = parse("permissions:a.b, c.d,e persistent:true");
        assertEquals(new LinkedHashSet<>(Arrays.asList("a.b", "c.d", "e")), flags.permissions());
        assertEquals(Boolean.TRUE, flags.persistent());
    }

    @Test
    public void emptyPermissionEntriesAreDropped() {
        assertEquals(new LinkedHashSet<>(Arrays.asList("a", "b")), parse("permissions:a,,b,").permissions());
        assertTrue(parse("permissions:,").permissions().isEmpty());
        assertTrue(parse("permissions:").permissions().isEmpty());
    }

    @Test
    public void emptyPasswordMeansNoPassword() {
        CreateFlags flags = parse("password: name:Alpha");
        assertTrue(flags.has("password"));
        assertNull(flags.password());
        assertEquals("Alpha", flags.name());
    }

    @Test
    public void passwordIsTheFirstWordOnly() {
        assertEquals("one", parse("password:one two three").password());
    }

    @Test
    public void persistentIsStrict() {
        assertEquals(Boolean.TRUE, parse("persistent:true").persistent());
        assertEquals(Boolean.FALSE, parse("persistent:false").persistent());
        assertNull(parse("persistent:True").persistent());
        assertNull(parse("persistent:yes").persistent());
        assertEquals("yes", parse("persistent:yes").persistentRaw());
        assertTrue(parse("persistent:yes").has("persistent"));
    }

    @Test
    public void colonInsideAValueStartsANewFlag() {
        CreateFlags flags = parse("name:Hello Foo:bar");
        assertEquals("Hello", flags.name());
        assertTrue(flags.has("Foo"));
    }

    @Test
    public void onlyTheFirstColonSplits() {
        assertEquals("a:b", parse("name:a:b").name());
        assertEquals("pw:x", parse("password:pw:x").password());
    }

    @Test
    public void lastDuplicateWins() {
        assertEquals("Second", parse("name:First name:Second").name());
    }

    @Test
    public void unknownKeysAndLeadingTokensAreIgnored() {
        CreateFlags flags = parse("hello world foo:bar name:Alpha");
        assertEquals("Alpha", flags.name());
        assertFalse(flags.has("hello"));
        assertNull(parse("hello world").name());
    }

    @Test
    public void keysAreCaseSensitive() {
        CreateFlags flags = parse("Name:Alpha");
        assertNull(flags.name());
        assertFalse(flags.has("name"));
    }

    @Test
    public void noArgumentsMeansNoFlags() {
        CreateFlags flags = CreateFlags.parse(new String[]{"create"});
        assertFalse(flags.has("name"));
        assertTrue(flags.usedFlags().isEmpty());
    }

    @Test
    public void usedFlagsSkipInvalidPersistent() {
        assertEquals(Arrays.asList("name"), parse("name:Alpha persistent:maybe").usedFlags());
        assertEquals(Arrays.asList("name", "persistent"), parse("persistent:false name:Alpha").usedFlags());
    }
}
