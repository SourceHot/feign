package feign.example;

import feign.Feign;
import feign.example.github.Contributor;
import feign.example.github.GitHubApi;
import feign.gson.GsonDecoder;

import java.lang.reflect.Method;
import java.util.List;

public class Demo {
    public static void main(String[] args) throws NoSuchMethodException {
        System.getProperties().put("sun.misc.ProxyGenerator.saveGeneratedFiles", "true");
        System.getProperties().put("jdk.proxy.ProxyGenerator.saveGeneratedFiles", "true");
        GitHubApi github = Feign.builder()
                .decoder(new GsonDecoder())
                .target(GitHubApi.class, "https://api.github.com");

        // Fetch and print a list of the contributors to this library.
        List<Contributor> contributors = github.contributors("OpenFeign", "feign");
        for (Contributor contributor : contributors) {
            System.out.println(contributor.getLogin() + " (" + contributor.getContributions() + ")");
        }
    }
}
