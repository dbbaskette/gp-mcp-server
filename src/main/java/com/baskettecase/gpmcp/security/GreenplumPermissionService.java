package com.baskettecase.gpmcp.security;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Service for querying Greenplum user permissions
 *
 * Uses Greenplum's system catalogs to discover what the current user can access.
 * This replaces application-level permission checking with GP's native RBAC.
 */
@Slf4j
@Service
public class GreenplumPermissionService {

    /**
     * Get current user information
     */
    public UserInfo getCurrentUser(JdbcTemplate jdbcTemplate) {
        String sql = "SELECT current_user AS username, session_user AS session";
        Map<String, Object> result = jdbcTemplate.queryForMap(sql);

        UserInfo userInfo = new UserInfo();
        userInfo.setCurrentUser((String) result.get("username"));
        userInfo.setSessionUser((String) result.get("session"));

        return userInfo;
    }

    /**
     * Get role information for current user
     */
    public RoleInfo getCurrentUserRole(JdbcTemplate jdbcTemplate) {
        String sql = """
            SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolcanlogin
            FROM pg_roles
            WHERE rolname = current_user
            """;

        Map<String, Object> result = jdbcTemplate.queryForMap(sql);

        RoleInfo roleInfo = new RoleInfo();
        roleInfo.setRoleName((String) result.get("rolname"));
        roleInfo.setSuperuser((Boolean) result.get("rolsuper"));
        roleInfo.setCanCreateDb((Boolean) result.get("rolcreatedb"));
        roleInfo.setCanCreateRole((Boolean) result.get("rolcreaterole"));
        roleInfo.setCanLogin((Boolean) result.get("rolcanlogin"));

        return roleInfo;
    }

    /**
     * Get roles/groups that current user is a member of
     */
    public List<String> getUserRoleMemberships(JdbcTemplate jdbcTemplate) {
        String sql = """
            SELECT m.roleid::regrole::text AS member_of
            FROM pg_auth_members m
            JOIN pg_roles u ON m.member = u.oid
            WHERE u.rolname = current_user
            ORDER BY 1
            """;

        return jdbcTemplate.queryForList(sql, String.class);
    }

    /**
     * Get databases accessible to current user
     */
    public List<DatabasePrivilege> getAccessibleDatabases(JdbcTemplate jdbcTemplate) {
        String sql = """
            SELECT d.datname,
                   has_database_privilege(d.datname, 'connect') AS can_connect,
                   has_database_privilege(d.datname, 'create')  AS can_create,
                   has_database_privilege(d.datname, 'temp')    AS can_temp
            FROM pg_database d
            WHERE d.datname NOT IN ('template0', 'template1')
            ORDER BY d.datname
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            DatabasePrivilege db = new DatabasePrivilege();
            db.setDatabaseName(rs.getString("datname"));
            db.setCanConnect(rs.getBoolean("can_connect"));
            db.setCanCreate(rs.getBoolean("can_create"));
            db.setCanTemp(rs.getBoolean("can_temp"));
            return db;
        });
    }

    /**
     * Get schemas accessible to current user in current database
     */
    public List<SchemaPrivilege> getAccessibleSchemas(JdbcTemplate jdbcTemplate) {
        String sql = """
            SELECT n.nspname AS schema,
                   has_schema_privilege(n.oid, 'usage')  AS usage,
                   has_schema_privilege(n.oid, 'create') AS create
            FROM pg_namespace n
            WHERE n.nspname NOT LIKE 'pg\\_%'
              AND n.nspname <> 'information_schema'
              AND n.nspname NOT LIKE 'gp\\_%'
            ORDER BY 1
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            SchemaPrivilege schema = new SchemaPrivilege();
            schema.setSchemaName(rs.getString("schema"));
            schema.setCanUse(rs.getBoolean("usage"));
            schema.setCanCreate(rs.getBoolean("create"));
            return schema;
        });
    }

    /**
     * Get complete permission info for current user
     */
    public PermissionInfo getCompletePermissions(JdbcTemplate jdbcTemplate) {
        PermissionInfo info = new PermissionInfo();

        try {
            info.setUserInfo(getCurrentUser(jdbcTemplate));
            info.setRoleInfo(getCurrentUserRole(jdbcTemplate));
            info.setRoleMemberships(getUserRoleMemberships(jdbcTemplate));
            info.setDatabases(getAccessibleDatabases(jdbcTemplate));
            info.setSchemas(getAccessibleSchemas(jdbcTemplate));
        } catch (Exception e) {
            log.error("Failed to query complete permissions", e);
            throw e;
        }

        return info;
    }

    /**
     * Data classes for permission information
     */

    @Data
    public static class UserInfo {
        private String currentUser;
        private String sessionUser;
    }

    @Data
    public static class RoleInfo {
        private String roleName;
        private Boolean superuser;
        private Boolean canCreateDb;
        private Boolean canCreateRole;
        private Boolean canLogin;
    }

    @Data
    public static class DatabasePrivilege {
        private String databaseName;
        private Boolean canConnect;
        private Boolean canCreate;
        private Boolean canTemp;
    }

    @Data
    public static class SchemaPrivilege {
        private String schemaName;
        private Boolean canUse;
        private Boolean canCreate;
    }

    @Data
    public static class PermissionInfo {
        private UserInfo userInfo;
        private RoleInfo roleInfo;
        private List<String> roleMemberships;
        private List<DatabasePrivilege> databases;
        private List<SchemaPrivilege> schemas;
    }
}
