package systems.porto.shinemedia.entry.rest;

import systems.porto.adapter.entry.AbstractEntryAdapter;
import systems.porto.api.route.CrudHttpRoutes;
import systems.porto.api.route.EntryHttpRoutes;
import systems.porto.api.route.RouteRegistrar;
import systems.porto.context.Context;

public class AuthTokenRestEntryAdapter extends AbstractEntryAdapter<Context> {

    @Override
    public String id() {
        return "auth-token-rest";
    }

    @Override
    public void start() {
        RouteRegistrar registrar = EntryHttpRoutes.registrar(getContext());
        String apiBasePath = EntryHttpRoutes.apiBasePath(getConfig());
        registrar.register(
            "POST",
            CrudHttpRoutes.join(apiBasePath, "/auth/token"),
            "auth-token",
            "auth-token-create"
        );
        registrar.register(
            "POST",
            CrudHttpRoutes.join(apiBasePath, "/auth/password"),
            "auth-token",
            "auth-password-update"
        );
    }

    @Override
    public void stop() {
    }
}
