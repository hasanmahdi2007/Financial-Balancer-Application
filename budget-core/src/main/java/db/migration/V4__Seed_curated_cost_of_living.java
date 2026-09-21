package db.migration;

import com.hasan.budget.costofliving.classpath.CuratedDataset;
import com.hasan.budget.shared.MetroId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Loads the committed CSVs into Postgres.
 *
 * <p>A Java migration rather than a file of INSERT statements, and the reason is worth stating: the
 * same files feed the classpath adapter. Hand-writing the SQL would create a second copy of every
 * figure, and the two copies would disagree the first time somebody edited one of them - which is
 * exactly the failure the shared contract test between the two providers exists to catch, caught
 * instead by nobody, because both adapters would still be internally consistent.
 *
 * <p>Nothing here reaches the network. The figures are committed files; a build, a test and a demo
 * all work with the machine offline, which matters because this one's DNS drops for minutes at a
 * time.
 */
public class V4__Seed_curated_cost_of_living extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        CuratedDataset dataset = CuratedDataset.load();
        Connection connection = context.getConnection();
        insertSources(connection, dataset);
        insertCountries(connection, dataset);
        Map<MetroId, Long> metroIds = insertMetros(connection, dataset);
        insertNationalBaselines(connection, dataset);
        insertCityBaselines(connection, dataset, metroIds);
    }

    private void insertSources(Connection connection, CuratedDataset dataset) throws Exception {
        String sql = "INSERT INTO data_source (id, name, url, retrieved_at, license_note) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (CuratedDataset.SourceRow source : dataset.sources()) {
                statement.setString(1, source.id());
                statement.setString(2, source.name());
                statement.setString(3, source.url());
                statement.setObject(4, source.retrievedAt());
                statement.setString(5, source.licenseNote());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertCountries(Connection connection, CuratedDataset dataset) throws Exception {
        String sql = "INSERT INTO country "
                + "(code, name, currency, annual_inflation_pct, inflation_as_of, data_note, listed) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (var country : dataset.countries()) {
                statement.setString(1, country.code().value());
                statement.setString(2, country.name());
                statement.setString(3, country.currency());
                // The domain holds basis points so its arithmetic stays exact and integer; the
                // column stays a percentage because that is what a person reading the table expects.
                statement.setBigDecimal(
                        4,
                        java.math.BigDecimal.valueOf(country.annualInflationBasisPoints(), 2));
                statement.setObject(5, country.inflationAsOf());
                statement.setString(6, country.dataNote());
                statement.setBoolean(7, country.listed());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Map<MetroId, Long> insertMetros(Connection connection, CuratedDataset dataset)
            throws Exception {
        String sql = "INSERT INTO metro_area "
                + "(country_code, slug, display_name, admin1, population, sort_rank) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        Map<MetroId, Long> ids = new HashMap<>();
        try (PreparedStatement statement =
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (CuratedDataset.MetroRow metro : dataset.metros()) {
                statement.setString(1, metro.country().value());
                statement.setString(2, metro.metro().slug());
                statement.setString(3, metro.displayName());
                statement.setString(4, metro.admin1());
                if (metro.population() == null) {
                    statement.setNull(5, Types.INTEGER);
                } else {
                    statement.setInt(5, metro.population());
                }
                statement.setInt(6, metro.sortRank());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    ids.put(metro.metro(), keys.getLong("id"));
                }
            }
        }
        return ids;
    }

    private void insertNationalBaselines(Connection connection, CuratedDataset dataset)
            throws Exception {
        String sql = "INSERT INTO national_baseline "
                + "(country_code, category, income_quintile, monthly_amount, confidence, source_id, "
                + "as_of, low_pct, high_pct) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (CuratedDataset.CountryFigure figure : dataset.countryFigures()) {
                statement.setString(1, figure.country().value());
                statement.setString(2, figure.category().name());
                setQuintile(statement, 3, figure.quintile());
                statement.setBigDecimal(4, figure.amount().amount());
                statement.setString(5, figure.confidence().name());
                statement.setString(6, figure.sourceId());
                statement.setObject(7, figure.asOf());
                statement.setInt(8, figure.band().lowPercent());
                statement.setInt(9, figure.band().highPercent());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertCityBaselines(
            Connection connection, CuratedDataset dataset, Map<MetroId, Long> metroIds)
            throws Exception {
        String sql = "INSERT INTO city_category_baseline "
                + "(metro_id, category, income_quintile, monthly_amount, confidence, source_id, as_of) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (CuratedDataset.CityFigure figure : dataset.cityFigures()) {
                Long metroId = metroIds.get(figure.metro());
                if (metroId == null) {
                    throw new IllegalStateException(
                            "seed data holds a figure for unlisted city " + figure.metro().slug());
                }
                statement.setLong(1, metroId);
                statement.setString(2, figure.category().name());
                setQuintile(statement, 3, figure.quintile());
                statement.setBigDecimal(4, figure.amount().amount());
                statement.setString(5, figure.confidence().name());
                statement.setString(6, figure.sourceId());
                statement.setObject(7, figure.asOf());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Curated rows have no quintile, so this writes NULL on every one of them - by design. */
    private void setQuintile(
            PreparedStatement statement, int index, com.hasan.budget.shared.IncomeQuintile quintile)
            throws Exception {
        if (quintile == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, quintile.name());
        }
    }
}
