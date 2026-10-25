package cn.wordflow.app;

/** Dependency-free checks, also invoked by JUnit in the Android build. */
public final class CsvParserChecks {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
        checks++;
    }
    private static void rejects(String csv) {
        try { CsvParser.parse(csv); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Invalid CSV was accepted");
    }
    public static void main(String[] args) throws Exception {
        checks = 0;
        var rows = CsvParser.parse("\uFEFFword,sentence\r\nhello,Hello there.\r\n");
        equal(1, rows.size()); equal("hello", rows.get(0).word);
        equal("She said, \"Remember.\"\nThen left.", CsvParser.parse("remember,\"She said, \"\"Remember.\"\"\nThen left.\"\n").get(0).sentence);
        equal(1, CsvParser.parse("\n词汇,例句\n\nhello,Hello!\n").size());
        equal("hello", CsvParser.parse("hello,Hello!").get(0).word);
        equal(1, CsvParser.parse("word,example\na,b").size());
        equal("world", CsvParser.parse("  hello  , world ").get(0).sentence);
        for (String invalid : new String[]{"", "word,sentence", ",Hello", "a,b,c", "a,\"open", "a,\"closed\"x", "a,b\"c", "a,\"b\"\""}) rejects(invalid);
        equal("", CsvParser.parse("hello,").get(0).sentence);
        equal("", CsvParser.parse("hello").get(0).sentence);
        equal(3, CsvParser.parse("word,sentence\nhello,\nworld,Hello world!\nlearn").size());
        rejects("a," + "b".repeat(3501));
        rejects("a,b\n".repeat(10001));
        equal(10000, CsvParser.parse("a,b\n".repeat(10000)).size());
        equal(3500, CsvParser.parse("a," + "b".repeat(3500)).get(0).sentence.length());
        equal(2, CsvParser.parse("a,b\rc,d\r").size());
        if (args.length > 0) {
            String sample = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])), java.nio.charset.StandardCharsets.UTF_8);
            equal(5, CsvParser.parse(sample).size());
        }
        System.out.println("CSV parser: " + checks + " checks passed");
    }
}
