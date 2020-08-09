package io.smallrye.graphql.execution.datafetcher;

import static io.smallrye.graphql.SmallRyeGraphQLServerMessages.msg;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.graphql.GraphQLException;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetchingEnvironment;
import io.smallrye.graphql.bootstrap.Config;
import io.smallrye.graphql.execution.datafetcher.decorator.DataFetcherDecorator;
import io.smallrye.graphql.schema.model.Operation;
import io.smallrye.graphql.transformation.AbstractDataFetcherException;

/**
 * Fetch data from resolvers that return {@code CompletionStage}.
 *
 * @author Yannick Bröker (ybroeker@techfak.uni-bielefeld.de)
 */
public class AsyncDataFetcher extends AbstractDataFetcher<CompletionStage<DataFetcherResult<Object>>> {

    public AsyncDataFetcher(Config config, Operation operation) {
        this(config, operation, Collections.emptyList());
    }

    public AsyncDataFetcher(Config config, Operation operation, Collection<DataFetcherDecorator> decorators) {
        super(config, operation, decorators);
    }

    /**
     * This makes the call on the method. We do the following:
     *
     * <ol>
     * <li>Get the correct instance of the class we want to make the call in using CDI. That allow the developer to still use
     * Scopes in the bean.
     * <li>Get the argument values (if any) from graphql-java and make sue they are in the correct type, and if needed,
     * transformed.
     * <li>Make a call on the method with the correct arguments ang get the result.
     * <li>transform the result (which is wrapped in {@code CompletionStage}, and wrap it in
     * {@code CompletionStage<DataFetcherResult>}.
     * </ol>
     *
     * @param dfe the Data Fetching Environment from graphql-java
     * @return the result from the call, wrapped in an (possibly asnyc completing) {@link CompletionStage}
     * @throws Exception if the resolver throws an exception
     * @throws DataFetcherException if the reflective call fails
     */
    @Override
    protected CompletionStage<DataFetcherResult<Object>> fetch(
            final DataFetcherResult.Builder<Object> resultBuilder,
            final DataFetchingEnvironment dfe) throws Exception {

        try {
            ExecutionContext executionContext = createExecutionContext(dfe);

            CompletionStage<Object> futureResult = execute(executionContext);

            return futureResult.handle((result, throwable) -> {
                if (throwable instanceof CompletionException) {
                    //Exception thrown by underlying method may be wrapped in CompletionException
                    throwable = throwable.getCause();
                }

                if (throwable != null) {
                    if (throwable instanceof GraphQLException) {
                        GraphQLException graphQLException = (GraphQLException) throwable;
                        appendPartialResult(resultBuilder, executionContext.dataFetchingEnvironment(), graphQLException);
                    } else if (throwable instanceof Exception) {
                        throw msg.dataFetcherException(operation, throwable);
                    } else if (throwable instanceof Error) {
                        throw ((Error) throwable);
                    }
                } else {
                    try {
                        resultBuilder.data(fieldHelper.transformResponse(result));
                    } catch (AbstractDataFetcherException te) {
                        te.appendDataFetcherResult(resultBuilder, executionContext.dataFetchingEnvironment());
                    }
                }

                return resultBuilder.build();
            });

        } catch (AbstractDataFetcherException pe) {
            //Arguments or result couldn't be transformed
            pe.appendDataFetcherResult(resultBuilder, dfe);
        } catch (GraphQLException graphQLException) {
            appendPartialResult(resultBuilder, dfe, graphQLException);
        } catch (SecurityException | IllegalAccessException | IllegalArgumentException ex) {
            //m.invoke failed
            throw msg.dataFetcherException(operation, ex);
        }

        return CompletableFuture.completedFuture(resultBuilder.build());
    }

}
