set -e; J=${JAVA_HOME:+$JAVA_HOME/bin}; J=${J:-$(dirname $(command -v javac))}; M=$(cd "$(dirname "$0")/../../app/src/main" && pwd)
cd "$(dirname "$0")"; rm -rf cls; mkdir cls
$J/javac -d cls $M/java/io/github/ziyuw_plank/gboardgloss/Dictionary.java Eval.java Dbg.java
$J/java -cp cls Eval ${ASSETS:-$M/assets} "$@"
