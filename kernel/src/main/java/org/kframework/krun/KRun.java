// Copyright (c) 2015-2016 K Team. All Rights Reserved.
package org.kframework.krun;

import org.apache.commons.lang3.tuple.Pair;
import org.kframework.attributes.Source;
import org.kframework.builtin.KLabels;
import org.kframework.builtin.Sorts;
import org.kframework.compile.ConfigurationInfoFromModule;
import org.kframework.definition.Module;
import org.kframework.definition.Rule;
import org.kframework.kompile.CompiledDefinition;
import org.kframework.kore.Assoc;
import org.kframework.kore.K;
import org.kframework.kore.KApply;
import org.kframework.kore.KLabel;
import org.kframework.kore.KORE;
import org.kframework.kore.KToken;
import org.kframework.kore.Sort;
import org.kframework.kore.Unapply.KApply$;
import org.kframework.kore.VisitK;
import org.kframework.kore.compile.KTokenVariablesToTrueVariables;
import org.kframework.krun.modes.ExecutionMode;
import org.kframework.parser.ProductionReference;
import org.kframework.parser.binary.BinaryParser;
import org.kframework.parser.kore.KoreParser;
import org.kframework.rewriter.Rewriter;
import org.kframework.unparser.AddBrackets;
import org.kframework.unparser.KOREToTreeNodes;
import org.kframework.unparser.OutputModes;
import org.kframework.unparser.ToBinary;
import org.kframework.unparser.ToKast;
import org.kframework.utils.errorsystem.KEMException;
import org.kframework.utils.errorsystem.KException;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.utils.errorsystem.ParseFailedException;
import org.kframework.utils.file.FileUtil;
import scala.Some;
import scala.Tuple2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.kframework.Collections.*;
import static org.kframework.kore.KORE.*;

/**
 * The KORE-based KRun
 */
public class KRun {

    private final KExceptionManager kem;
    private final FileUtil files;
    private final boolean ttyStdin;
    private final boolean isNailgun;

    public KRun(KExceptionManager kem, FileUtil files, boolean ttyStdin, boolean isNailgun) {
        this.kem = kem;
        this.files = files;
        this.ttyStdin = ttyStdin;
        this.isNailgun = isNailgun;
    }


    public int run(CompiledDefinition compiledDef, KRunOptions options, Function<Module, Rewriter> rewriterGenerator, ExecutionMode executionMode) {
        String pgmFileName = options.configurationCreation.pgm();
        K program;
        if (options.configurationCreation.term()) {
            program = externalParse(options.configurationCreation.parser(compiledDef.executionModule().name()),
                    pgmFileName, compiledDef.programStartSymbol, Source.apply("<parameters>"), compiledDef, files);
        } else {
            program = parseConfigVars(options, compiledDef, kem, files, ttyStdin, isNailgun, null);
        }

        program = new KTokenVariablesToTrueVariables()
                .apply(compiledDef.kompiledDefinition.getModule(compiledDef.mainSyntaxModuleName()).get(), program);


        Rewriter rewriter = rewriterGenerator.apply(compiledDef.executionModule());

        Object result = executionMode.execute(program, rewriter, compiledDef);


        if (result instanceof K) {
            printK((K) result, options, compiledDef);
            if (options.exitCodePattern != null) {
                Rule exitCodePattern = compilePattern(files, kem, options.exitCodePattern, options, compiledDef, Source.apply("<command line: --exit-code>"));
                K res = rewriter.match((K) result, exitCodePattern);
                return getExitCode(kem, res);
            }
        } else if (result instanceof Tuple2) {
            Tuple2<?, ?> tuple = (Tuple2<?, ?>) result;
            if (tuple._1() instanceof K && tuple._2() instanceof Integer) {
                prettyPrint(compiledDef, options.output, s -> outputFile(s, options), (K) tuple._1());
                return (Integer) tuple._2();
            }
            if (tuple._1() instanceof K && tuple._2() instanceof Integer) {
                printK((K) tuple._1(), options, compiledDef);
                return (Integer) tuple._2();
            }
        } else if (options.experimental.prove != null) {
            if (((List) result).isEmpty()) {
                System.out.println("true");
            }
        } else if (result instanceof Integer) {
            return (Integer) result;
        }
        return 0;
    }

    private void printConjunction(K conjunction, KRunOptions options, CompiledDefinition compiledDef) {
        Some<Tuple2<KLabel, scala.collection.immutable.List<K>>> searchResults = KApply$.MODULE$.unapply((KApply) conjunction);
        if (searchResults != null && searchResults.get()._1().equals(KLabel(KLabels.AND)) && searchResults.get()._2().size() >= 2) {
            prettyPrint(compiledDef, options.output, s -> outputFile(s, options), searchResults.get()._2().apply(0));
            outputFile("Constraint \n", options);
            prettyPrint(compiledDef, options.output, s -> outputFile(s, options), searchResults.get()._2().apply(1));
            return;
        }
        prettyPrint(compiledDef, options.output, s -> outputFile(s, options), conjunction);

    }

    public void printK(K result, KRunOptions options, CompiledDefinition compiledDef) {
        Some<Tuple2<KLabel, scala.collection.immutable.List<K>>> searchResults = KApply$.MODULE$.unapply((KApply) result);
        if (searchResults.get() != null && searchResults.get()._1().equals(KLabel(KLabels.OR))) {
            scala.collection.Seq<K> resultList = Assoc.flatten(KORE.KLabel(KLabels.OR), searchResults.get()._2(), KORE.KLabel(KLabels.ML_FALSE));
            int i = 1;
            while (i < resultList.size()) {
                outputFile("Solution " + i + "\n", options);
                printConjunction(resultList.apply(i++), options, compiledDef);
            }
            return;
        }
        prettyPrint(compiledDef, options.output, s -> outputFile(s, options), result);
    }

    /**
     * Function to return the exit code specified by the user given a substitution
     *
     * @param kem ExcpetionManager object
     * @param res The substitution from the match of the user specified pattern on the Final Configuration.
     * @return An int representing the error code.
     */
    public static int getExitCode(KExceptionManager kem, K res) {
        Some<Tuple2<KLabel, scala.collection.immutable.List<K>>> searchResults = KApply$.MODULE$.unapply((KApply) res);
        scala.collection.Seq<K> flatList = Assoc.flatten(KORE.KLabel(KLabels.OR), searchResults.get()._2(), KLabel(KLabels.ML_FALSE));
        if (flatList.size() != 2) {
            kem.registerCriticalWarning("Found " + flatList.size() + " solutions to exit code pattern. Returning 112.");
            return 112;
        }
        K solution = flatList.apply(1);
        Set<Integer> vars = new HashSet<>();
        new VisitK() {
            @Override
            public void apply(KToken t) {
                if (Sorts.Int().equals(t.sort())) {
                    try {
                        vars.add(Integer.valueOf(t.s()));
                    } catch (NumberFormatException e) {
                        throw KEMException.criticalError("Exit code found was not in the range of an integer. Found: " + t.s(), e);
                    }
                }

            }
        }.apply(solution);

        if (vars.size() != 1) {
            kem.registerCriticalWarning("Found " + vars.size() + " integer variables in exit code pattern. Returning 111.");
            return 111;
        }
        return vars.iterator().next();
    }

    //TODO(dwightguth): use Writer
    public void outputFile(String output, KRunOptions options) {
        outputFile(output.getBytes(), options);
    }

    public void outputFile(byte[] output, KRunOptions options) {
        outputFile(output, options, files);
    }

    public static void outputFile(byte[] output, KRunOptions options, FileUtil files) {
        if (options.outputFile == null) {
            try {
                System.out.write(output);
            } catch (IOException e) {
                throw KEMException.internalError(e.getMessage(), e);
            }
        } else {
            files.saveToWorkingDirectory(options.outputFile, output);
        }
    }

    /**
     * Function to compile the String Pattern, if the pattern is not present in the cache. Note the difference between
     * compilation and parsing. Compilation is the result of resolving anonymous variables, semantic casts, and concretizing
     * sentences after parsing the pattern string.
     *
     * @param pattern The String specifying the pattern to be compiled
     * @param source  Source of the pattern, usually either command line or file path.
     * @return The pattern (represented by a Rule object) obtained from the compilation process.
     */
    public static Rule compilePattern(FileUtil files, KExceptionManager kem, String pattern, KRunOptions options, CompiledDefinition compiledDef, Source source) {
        if (pattern != null && (options.experimental.prove != null || options.experimental.ltlmc())) {
            throw KEMException.criticalError("Pattern matching is not supported by model checking or proving");
        }
        return compiledDef.compilePatternIfAbsent(files, kem, pattern, source);
    }

    /**
     * Function to parse the String Pattern. It's the step in the compilation process that occurs before resoving anonymous variables,
     * semantic casts, and sentence concretizaiton
     *
     * @param pattern The String representing the pattern to be parsed.
     * @param source  The Source of the pattern, usually either the command line or the file path.
     * @return The pattern (represented by a Rule object) obtained from the parsing process.
     */
    public static Rule parsePattern(FileUtil files, KExceptionManager kem, String pattern, CompiledDefinition compiledDef, Source source) {
        return compiledDef.parsePatternIfAbsent(files, kem, pattern, source);
    }

    public static void prettyPrint(CompiledDefinition compiledDef, OutputModes output, Consumer<byte[]> print, K result) {
        switch (output) {
        case KAST:
            print.accept((ToKast.apply(result) + "\n").getBytes());
            break;
        case NONE:
            print.accept("".getBytes());
            break;
        case PRETTY:
            Module unparsingModule = compiledDef.getExtensionModule(compiledDef.languageParsingModule());
            print.accept((unparseTerm(result, unparsingModule) + "\n").getBytes());
            break;
        case BINARY:
            print.accept(ToBinary.apply(result));
            break;
        default:
            throw KEMException.criticalError("Unsupported output mode: " + output);
        }
    }

    public static Map<KToken, K> getUserConfigVarsMap(KRunOptions options, CompiledDefinition compiledDef, FileUtil files) {
        Map<KToken, K> output = new HashMap<>();
        for (Map.Entry<String, Pair<String, String>> entry
                : options.configurationCreation.configVars(compiledDef.getParsedDefinition().mainModule().name()).entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue().getLeft();
            String parser = entry.getValue().getRight();
            Sort sort = compiledDef.configurationVariableDefaultSorts.get("$" + name);
            assert sort != null : "Could not find configuration variable: $" + name;
            K configVar = externalParse(parser, value, sort, Source.apply("<command line: -c" + name + ">"), compiledDef, files);
            output.put(KToken("$" + name, Sorts.KConfigVar()), configVar);
        }
        return output;
    }

    public static Map<KToken, K> getIOConfigVarsMap(boolean realIO, boolean ttyStdin, boolean isNailgun) {
        Map<KToken, K> output = new HashMap<>();
        if (realIO) {
            output.put(KToken("$STDIN", Sorts.KConfigVar()), KToken("\"\"", Sorts.String()));
            output.put(KToken("$IO", Sorts.KConfigVar()), KToken("\"on\"", Sorts.String()));
        } else {
            String stdin = getStdinBuffer(ttyStdin, isNailgun);
            output.put(KToken("$STDIN", Sorts.KConfigVar()), KToken("\"" + stdin + "\"", Sorts.String()));
            output.put(KToken("$IO", Sorts.KConfigVar()), KToken("\"off\"", Sorts.String()));
        }
        return output;
    }

    public static Map<KToken, K> getPGMConfigVarsMap(K pgm) {
        Map<KToken, K> output = new HashMap<>();
        if (pgm != null) {
            output.put(KToken("$PGM", Sorts.KConfigVar()), pgm);
        }
        return output;
    }

    public static K parseConfigVars(KRunOptions options, CompiledDefinition compiledDef, KExceptionManager kem, FileUtil files, boolean ttyStdin, boolean isNailgun, K pgm) {
        Map<KToken, K> output = getUserConfigVarsMap(options, compiledDef, files);
        return getInitConfig(pgm, output, compiledDef, kem, options.io(), ttyStdin, isNailgun);
    }

    public static K getInitConfig(K pgm, CompiledDefinition compiledDef, KExceptionManager kem) {
        return getInitConfig(pgm, new HashMap<>(), compiledDef, kem, true, true, false);
    }

    public static K getInitConfig(K pgm, Map<KToken, K> outputUser, CompiledDefinition compiledDef, KExceptionManager kem,
                                  boolean realIO, boolean ttyStdin, boolean isNailgun) {
        Map<KToken, K> outputIO = getIOConfigVarsMap(realIO, ttyStdin, isNailgun);
        Map<KToken, K> outputPGM = getPGMConfigVarsMap(pgm);

        Map<KToken, K> output = new HashMap<>();
        output.putAll(outputUser);
        output.putAll(outputIO);
        output.putAll(outputPGM);

        checkConfigVars(output.keySet(), compiledDef, kem);
        return plugConfigVars(compiledDef, output);
    }

    private static void checkConfigVars(Set<KToken> inputConfigVars, CompiledDefinition compiledDef, KExceptionManager kem) {
        Set<KToken> defConfigVars = mutable(new ConfigurationInfoFromModule(compiledDef.kompiledDefinition.mainModule()).configVars());

        for (KToken defConfigVar : defConfigVars) {
            if (!inputConfigVars.contains(defConfigVar)) {
                throw KEMException.compilerError("Configuration variable missing: " + defConfigVar.s());
            }
        }

        for (KToken inputConfigVar : inputConfigVars) {
            if (!defConfigVars.contains(inputConfigVar)) {
                if (!inputConfigVar.s().equals("$STDIN") && !inputConfigVar.s().equals("$IO")) {
                    kem.registerCompilerWarning("User specified configuration variable " + inputConfigVar.s() + " which does not exist.");
                }
            }
        }
    }

    public static String getStdinBuffer(boolean ttyStdin, boolean isNailgun) {
        String buffer = "";

        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(System.in));
            // detect if the input comes from console or redirected
            // from a pipeline

            if ((isNailgun && !ttyStdin)
                    || (!isNailgun && br.ready())) {
                buffer = br.readLine();
            }
        } catch (IOException e) {
            throw KEMException.internalError("IO error detected reading from stdin", e);
        }
        if (buffer == null || buffer.equals("")) {
            return "";
        }
        return buffer + "\n";
    }

    public static KApply plugConfigVars(CompiledDefinition compiledDef, Map<KToken, K> output) {
        return KApply(compiledDef.topCellInitializer, output.entrySet().stream().map(e -> KApply(KLabel("_|->_"), e.getKey(), e.getValue())).reduce(KApply(KLabel(".Map")), (a, b) -> KApply(KLabel("_Map_"), a, b)));
    }

    private static String unparseTerm(K input, Module test) {
        return KOREToTreeNodes.toString(
                new AddBrackets(test).addBrackets((ProductionReference)
                        KOREToTreeNodes.apply(KOREToTreeNodes.up(test, input), test)));
    }

    public static K externalParse(String parser, String value, Sort startSymbol, Source source, CompiledDefinition compiledDef, FileUtil files) {
        List<String> tokens = new ArrayList<>(Arrays.asList(parser.split(" ")));
        tokens.add(value);
        Map<String, String> environment = new HashMap<>();
        environment.put("KRUN_SORT", startSymbol.name());
        environment.put("KRUN_COMPILED_DEF", files.resolveDefinitionDirectory(".").getAbsolutePath());
        RunProcess.ProcessOutput output = RunProcess.execute(environment, files.getProcessBuilder(), tokens.toArray(new String[tokens.size()]));

        if (output.exitCode != 0) {
            throw new ParseFailedException(new KException(KException.ExceptionType.ERROR, KException.KExceptionGroup.CRITICAL, "Parser returned a non-zero exit code: "
                    + output.exitCode + "\nStdout:\n" + new String(output.stdout) + "\nStderr:\n" + new String(output.stderr)));
        }

        byte[] kast = output.stdout != null ? output.stdout : new byte[0];
        if (BinaryParser.isBinaryKast(kast)) {
            return BinaryParser.parse(kast);
        } else {
            return KoreParser.parse(new String(kast), source);
        }
    }
}
