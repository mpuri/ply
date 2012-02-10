package net.ocheyedan.ply.cmd.config;

import net.ocheyedan.ply.FileUtil;
import net.ocheyedan.ply.OutputExt;
import net.ocheyedan.ply.PlyUtil;
import net.ocheyedan.ply.PropertiesFileUtil;
import net.ocheyedan.ply.cmd.Args;
import net.ocheyedan.ply.cmd.Command;
import net.ocheyedan.ply.cmd.Usage;
import net.ocheyedan.ply.props.Context;
import net.ocheyedan.ply.props.Scope;

import java.util.Properties;

/**
 * User: blangel
 * Date: 12/30/11
 * Time: 2:52 PM
 *
 * A {@link net.ocheyedan.ply.cmd.Command} to set a property value within the project's configuration.
 */
public final class Set extends Command.ProjectReliant {

    static class Opts {
        final Scope scope;
        final Context context;
        final String propName;
        final String propValue;

        Opts(Scope scope, Context context, String propName, String propValue) {
            this.scope = scope;
            this.context = context;
            this.propName = propName;
            this.propValue = propValue;
        }
    }

    public Set(Args args) {
        super(args);
    }

    @Override protected void runAfterAssumptionsCheck() {
        OutputExt.init(); // dis-regard ad-hoc props and defined properties, simply init
        super.run();
        Opts opts = parse(args);
        if (opts == null) {
            new Usage(args).run();
            return;
        }
        String path = FileUtil.pathFromParts(PlyUtil.LOCAL_CONFIG_DIR.getPath(), opts.context.name
                + opts.scope.getFileSuffix() + ".properties");
        Properties properties = PropertiesFileUtil.load(path, true);
        properties.setProperty(opts.propName, opts.propValue);
        PropertiesFileUtil.store(properties, path, true);
    }

    @SuppressWarnings("fallthrough")
    Opts parse(Args args) {
        Scope scope = Scope.Default;
        int scopeIndex = args.args.get(0).indexOf(":");
        if (scopeIndex != -1) {
            scope = Scope.named(args.args.get(0).substring(0, scopeIndex));
        }
        switch (args.args.size()) {
            case 4:
                if ("in".equals(args.args.get(2)) && args.args.get(1).contains("=")) {
                    int index = args.args.get(1).indexOf("=");
                    String propName = args.args.get(1).substring(0, index);
                    String propValue = args.args.get(1).substring(index + 1);
                    return new Opts(scope, new Context(args.args.get(3)), propName, propValue);
                } // fall-through
            default:
                return null;
        }
    }
}
