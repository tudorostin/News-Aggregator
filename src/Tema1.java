
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Tema1 {

    protected static Set<String> targetLanguages;
    protected static Set<String> targetCategories;
    protected static Set<String> excludedWords;

    // mapare uuid-articol pt articolele valide
    protected static ConcurrentMap<String, Article> validArticles = new ConcurrentHashMap<>();

    // (cheie: titlu, valoare: uuid ul articolului care a retinut titlul)
    protected static ConcurrentMap<String, String> seenTitles = new ConcurrentHashMap<>();

    protected static ConcurrentMap<String, Queue<String>> globalCategoryMap = new ConcurrentHashMap<>();
    protected static ConcurrentMap<String, Queue<String>> globalLanguageMap = new ConcurrentHashMap<>();

    protected static ConcurrentMap<String, Integer> globalKeywordCounts = new ConcurrentHashMap<>();
    // contor global pentru autori
    protected static ConcurrentMap<String, Integer> globalAuthorCounts = new ConcurrentHashMap<>();

    // BARIERA
    public static java.util.concurrent.CyclicBarrier barrier;

    public static AtomicInteger fileIndex = new AtomicInteger(0);

    // blacklist urile pt logica de duplicate
    protected static ConcurrentHashMap.KeySetView<String, Boolean> blacklistedUUIDs = ConcurrentHashMap.newKeySet();
    protected static ConcurrentHashMap.KeySetView<String, Boolean> blacklistedTitles = ConcurrentHashMap.newKeySet();

    // Contor duplicate
    public static AtomicInteger duplicatesCount = new AtomicInteger(0);

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: java Tema1 <numar_threads> <fisier_articole> <fisier_suplimentar>");
            return;
        }

        int numThreads = Integer.parseInt(args[0]);
        String articlesIndexFile = args[1];
        String auxIndexFile = args[2];
        barrier = new java.util.concurrent.CyclicBarrier(numThreads);

        try {
            List<String> articleFiles = readArticlesList(articlesIndexFile);
            List<String> auxFiles = readAuxiliaryList(auxIndexFile);

            if (auxFiles.size() >= 3) {
                targetLanguages = loadFileToSet(auxFiles.get(0));
                targetCategories = loadFileToSet(auxFiles.get(1));
                excludedWords = loadFileToSet(auxFiles.get(2));
            } else {
                System.err.println("Eroare: Fisierul auxiliar nu contine cele 3 cai necesare.");
                return;
            }

            NewsWorker[] workers = new NewsWorker[numThreads];
            Thread[] threads = new Thread[numThreads];
            for (int i = 0; i < numThreads; i++) {
                workers[i] = new NewsWorker(i, articleFiles);
                threads[i] = new Thread(workers[i]);
                threads[i].start();
            }

            for (int i = 0; i < numThreads; i++) {
                threads[i].join();
            }

            List<Article> finalArticles = new ArrayList<>(validArticles.values());

            // sortare pt raportarea corecta a most_recent article + ordine buna la output
            finalArticles.sort((a1, a2) -> {
                // sortare articole dupa data aparitiei
                int dateComparison = a2.getPublished().compareTo(a1.getPublished());
                if (dateComparison != 0) {
                    return dateComparison;
                }
                // daca au aceiasi data, compar uuid urile
                return a1.getUuid().compareTo(a2.getUuid());
            });

            processAllArticles(finalArticles);
            writeCategories();
            writeLanguages();
            writeKeywordReport();
            generateReports(finalArticles);

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    // --- FUNCTIILE DE CITIRE cu cai absolute + LOAD SET ul---

    private static List<String> readArticlesList(String filePath) throws IOException {
        List<String> files = new ArrayList<>();
        File indexFile = new File(filePath);
        File parentDir = indexFile.getParentFile();

        try (BufferedReader br = new BufferedReader(new FileReader(indexFile))) {
            String line = br.readLine();
            if (line != null && !line.trim().isEmpty()) {
                int n = Integer.parseInt(line.trim());
                for (int i = 0; i < n; i++) {
                    String fileLine = br.readLine();
                    if (fileLine != null) {
                        // construiesc folosind calea absoluta
                        File absFile = new File(parentDir, fileLine.trim());
                        files.add(absFile.getAbsolutePath());
                    }
                }
            }
        }
        return files;
    }

    private static List<String> readAuxiliaryList(String filePath) throws IOException {
        List<String> files = new ArrayList<>();
        File indexFile = new File(filePath);
        File parentDir = indexFile.getParentFile();

        try (BufferedReader br = new BufferedReader(new FileReader(indexFile))) {
            String line = br.readLine();
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    // calea absoluta
                    File absFile = new File(parentDir, line.trim());
                    files.add(absFile.getAbsolutePath());
                }
            }
        }
        return files;
    }

    private static Set<String> loadFileToSet(String filePath) throws IOException {
        Set<String> set = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line = br.readLine(); // Prima linie număr elemente (ignorat în Set)
            while ((line = br.readLine()) != null) {
                set.add(line.trim());
            }
        }
        return set;
    }

    private static void processAllArticles(List<Article> articles) {
        String fileName = "all_articles.txt";

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            for (Article article : articles) {
                writer.write(article.getUuid() + " " + article.getPublished());
                writer.write("\n");
            }

        } catch (IOException e) {
            System.err.println("Eroare la scrierea in fisierul " + fileName);
            e.printStackTrace();
        }
    }

    //FUNCTIA DE ORGANIZARE PE CATEGORII
    private static void writeCategories() {
        for (Map.Entry<String, java.util.Queue<String>> entry : Tema1.globalCategoryMap.entrySet()) {
            String originalName = entry.getKey();
            // convertire coada in lista pt a o sorta
            List<String> uuids = new ArrayList<>(entry.getValue());
            Collections.sort(uuids);

            String normalizedName = getNormalizedFileName(originalName);
            String fileName = normalizedName + ".txt";

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
                for (String uuid : uuids) {
                    writer.write(uuid);
                    writer.write("\n");
                }
            } catch (IOException e) {
                System.err.println("Eroare scriere categorie: " + fileName);
            }
        }
    }

    private static void writeLanguages() {
        for (Map.Entry<String, java.util.Queue<String>> entry : Tema1.globalLanguageMap.entrySet()) {
            String languageName = entry.getKey();
            // convertire + sortare
            List<String> uuids = new ArrayList<>(entry.getValue());
            Collections.sort(uuids);

            // normalizare nume fisier
            String normalizedName = getNormalizedFileName(languageName);
            String fileName = normalizedName + ".txt";

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
                for (String uuid : uuids) {
                    writer.write(uuid);
                    writer.write("\n");
                }
            } catch (IOException e) {
                System.err.println("Eroare scriere limba: " + fileName);
            }
        }
    }

    // functie pt normalizarea numelui fisierului
    private static String getNormalizedFileName(String categoryName) {
        return categoryName
                .replace(",", "")      // elimin virgulele
                .trim()                // curat spațiile de la capete
                .replaceAll("\\s+", "_"); // inlocuiesc spațiile interne cu underscore
    }

    private static void writeKeywordReport() {

        List<Map.Entry<String, Integer>> sortedKeywords = new ArrayList<>(Tema1.globalKeywordCounts.entrySet());

        sortedKeywords.sort((a, b) -> {
            int countCompare = b.getValue().compareTo(a.getValue());
            if (countCompare != 0) {
                return countCompare;
            }
            return a.getKey().compareTo(b.getKey());
        });

        String fileName = "keywords_count.txt";

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            for (Map.Entry<String, Integer> entry : sortedKeywords) {
                // format: <cuvant> <count>
                writer.write(entry.getKey() + " " + entry.getValue());
                writer.write("\n");
            }
        } catch (IOException e) {
            System.err.println("Eroare scriere keywords: " + fileName);
            e.printStackTrace();
        }
    }

    private static void generateReports(List<Article> sortedArticles) {
        String fileName = "reports.txt";

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            writer.write("duplicates_found - " + Tema1.duplicatesCount.get() + "\n");
            writer.write("unique_articles - " + sortedArticles.size() + "\n");
            writeTopEntryFromCounts(writer, "best_author", Tema1.globalAuthorCounts, false);

            Map<String, Integer> langCounts = new HashMap<>();
            for (var entry : Tema1.globalLanguageMap.entrySet()) {
                langCounts.put(entry.getKey(), entry.getValue().size());
            }
            writeTopEntryFromCounts(writer, "top_language", langCounts, false);

            Map<String, Integer> catCounts = new HashMap<>();
            for (var entry : Tema1.globalCategoryMap.entrySet()) {
                catCounts.put(entry.getKey(), entry.getValue().size());
            }
            writeTopEntryFromCounts(writer, "top_category", catCounts, true);

            if (!sortedArticles.isEmpty()) {
                Article mostRecent = sortedArticles.getFirst();
                writer.write("most_recent_article - " + mostRecent.getPublished() + " " + mostRecent.getUrl() + "\n");
            } else {
                writer.write("most_recent_article - None\n");
            }

            writeTopEntryFromCounts(writer, "top_keyword_en", Tema1.globalKeywordCounts, false);

        } catch (IOException e) {
            System.err.println("Eroare scriere reports.txt");
        }
    }

    // functie care calculeaza maximul
    private static void writeTopEntryFromCounts(BufferedWriter writer, String label, Map<String, Integer> map, boolean normalizeKey) throws IOException {
        if (map.isEmpty()) {
            return;
        }

        Map.Entry<String, Integer> topEntry = Collections.min(map.entrySet(), (e1, e2) -> {
            int res = e2.getValue().compareTo(e1.getValue());
            if (res == 0) {
                return e1.getKey().compareTo(e2.getKey());
            }
            return res;
        });

        String key = topEntry.getKey();
        if (normalizeKey) {
            key = key.replace(",", "").trim().replaceAll("\\s+", "_");
        }

        writer.write(label + " - " + key + " " + topEntry.getValue() + "\n");
    }
}