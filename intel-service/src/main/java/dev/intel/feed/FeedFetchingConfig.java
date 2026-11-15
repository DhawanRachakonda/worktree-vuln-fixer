package dev.intel.feed;

import dev.intel.config.IntelProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeedFetchingConfig {

    /** Never follows redirects on its own; the fetcher re-validates every hop. */
    @Bean
    HttpClient feedHttpClient(IntelProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.feed().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    DestinationValidator destinationValidator(IntelProperties properties) {
        return DestinationValidator.forProduction(properties.feed().allowedHosts());
    }

    @Bean
    FeedFetcher feedFetcher(HttpClient feedHttpClient, DestinationValidator validator, IntelProperties properties) {
        return new FeedFetcher(feedHttpClient, validator, properties.feed());
    }
}
