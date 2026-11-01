Room writes the exported database schema (`app.projectsteps.data.AppDatabase/1.json`) here on the
first build. Commit it. Instrumented migration tests read these files, and every future schema
version must add a migration in `AppDatabase.MIGRATIONS` (destructive fallback is never enabled).
