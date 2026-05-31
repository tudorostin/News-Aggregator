
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NewsWorker implements Runnable {
    private final int id;
    private final List<String> files;

    public NewsWorker(int id, List<String> files) {
        this.id = id;
        this.files = files;
    }

    @Override
    public void run() {
        ObjectMapper mapper = new ObjectMapper();
        List<Article> myCandidates = new ArrayList<>();

        while (true) {
            int i = Tema1.fileIndex.getAndIncrement();
            if (i >= files.size()) {
                break;
            }

            String filename = files.get(i);

            try {
                File file = new File(filename);
                if (!file.exists()) {
                    System.err.println("Thread " + id + ": Fisierul nu exista: " + filename);
                    continue;
                }

                Article[] articles = mapper.readValue(file, Article[].class);

                //procesarea articolelor
                for (Article article : articles) {

                    String uuid = article.getUuid();
                    String title = article.getTitle();

                    // UNICITATE UUID
                    Article existingUUID = Tema1.validArticles.putIfAbsent(uuid, article);
                    if (existingUUID != null) {
                        Tema1.duplicatesCount.incrementAndGet();
                        Tema1.blacklistedUUIDs.add(uuid);

                        // sterg originalul - conflict uuid
                        Article removeUUID = Tema1.validArticles.remove(uuid);
                        if (removeUUID != null) {
                            Tema1.duplicatesCount.incrementAndGet();
                            Tema1.blacklistedTitles.add(removeUUID.getTitle());
                        }
                        continue; // este duplicat, trec mai departe
                    }

                    // UNICITATEA TITLULUI
                    String existingUUIDforTitle = Tema1.seenTitles.putIfAbsent(title, uuid);

                    if (existingUUIDforTitle != null) {
                        // conflict titlu => invalidez ambele articole
                        Tema1.blacklistedTitles.add(title);
                        Tema1.blacklistedUUIDs.add(uuid);
                        Tema1.blacklistedUUIDs.add(existingUUIDforTitle);

                        // sterg ce am adaugat unicitate uuid
                        Article removedSelf = Tema1.validArticles.remove(uuid);
                        if (removedSelf != null) {
                            Tema1.duplicatesCount.incrementAndGet();
                        }

                        // sterg articolul care detinea titlul
                        Article removedOther = Tema1.validArticles.remove(existingUUIDforTitle);
                        if (removedOther != null) {
                            Tema1.duplicatesCount.incrementAndGet();
                        }

                        continue; // duplicat, nu il salvez
                    }
                    myCandidates.add(article);
                }

            } catch (IOException e) {
                System.err.println("Thread " + id + " eroare la fisierul " + filename + ": " + e.getMessage());
            }
        }

        // astept ca toate thread urile sa termine cu duplicatele
        try {
            Tema1.barrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            e.printStackTrace();
        }


        for (Article article : myCandidates) {

            if (Tema1.validArticles.containsKey(article.getUuid())) {
                String uuid = article.getUuid();

                // CATEGORII
                if (article.getCategories() != null) {
                    // set pt a elimina duplicatele din json
                    Set<String> uniqueCategoriesInArticle = new HashSet<>(article.getCategories());
                    for (String category : uniqueCategoriesInArticle) {
                        if (Tema1.targetCategories.contains(category)) {
                            Tema1.globalCategoryMap
                                    .computeIfAbsent(category, k -> new ConcurrentLinkedQueue<>())
                                    .add(uuid);
                        }
                    }
                }

                // LIMBI
                if (article.getLanguage() != null && Tema1.targetLanguages.contains(article.getLanguage())) {
                    Tema1.globalLanguageMap
                            .computeIfAbsent(article.getLanguage(), k -> new ConcurrentLinkedQueue<>())
                            .add(uuid);
                }

                // CUVINTE DE INTERES
                if ("english".equals(article.getLanguage()) && article.getText() != null) {
                    Set<String> uniqueWordsInArticle = new HashSet<>();
                    String text = article.getText().toLowerCase();

                    // separ de spatii
                    String[] tokens = text.split("\\s+");

                    for (String token : tokens) {
                        // sterg orice caracter care nu e litera
                        String clean = token.replaceAll("[^a-z]", "");

                        if (!clean.isEmpty() && !Tema1.excludedWords.contains(clean)) {
                            uniqueWordsInArticle.add(clean);
                        }
                    }

                    // adaug in contorul global
                    for (String word : uniqueWordsInArticle) {
                        Tema1.globalKeywordCounts.merge(word, 1, Integer::sum);
                    }
                }
                // RAPOARTE STATICE
                if (Tema1.validArticles.containsKey(article.getUuid())) {
                    // aici numar autorii
                    if (article.getAuthor() != null) {
                        Tema1.globalAuthorCounts.merge(article.getAuthor(), 1, Integer::sum);
                    }
                }
            }
        }
    }
}