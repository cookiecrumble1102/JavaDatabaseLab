import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;


public class SQLParser{

    private static class Where{
        final Column column;
        final Object value;

        Where(Column column, Object value){
            this.column = column;
            this.value = value;
        }
    }

    private Where parseWhere(String tableName){
        if (!accept("WHERE")) {
            return null;
        }

        String columnName = readIdentifier();
        Column column = manager.getColumn(tableName, columnName);

        String operator;

        if (accept("=")) {
            operator = "=";
        }

        else if (accept(">=")) {
            operator = ">=";
        }

        else if (accept(">")) {
            operator = ">";
        }

        else if (accept("<=")) {
            operator = "<=";
        }

        else if (accept("<")) {
            operator = "<";
        }

        else {
            throw new IllegalArgumentException(
                    "Ожидался оператор =, >, >=, < или <=."
            );
        }

        Object value = readValue(column);

        if (operator.equals("=")) {
            return new Where(column, value);
        }

        if (column.getType() != Column.DataType.INT && column.getType() != Column.DataType.DOUBLE) {
            throw new IllegalArgumentException("Сравнения >, >=, < и <= пока доступны только для INT и DOUBLE.");
        }

        if (value == null) {
            throw new IllegalArgumentException("Граница числового диапазона не может быть NULL.");
        }

        Number boundary = (Number) value;
        Range<Number> range;

        if (operator.equals(">") || operator.equals(">="))
        {
            range = new Range<>(
                    boundary,
                    null,
                    operator.equals(">="),
                    false
            );
        }
        else
        {
            range = new Range<>(
                    null,
                    boundary,
                    false,
                    operator.equals("<=")
            );
        }

        return new Where(column, range);
    }

    private List<Token> tokens;
    private int position;

    private final DatabaseManager manager;

    private enum TokenType{
        WORD,
        NUMBER,
        STRING,
        SYMBOL
    }

    private static class Token{
        final TokenType type;
        final String text;

        Token(TokenType type, String text){
            this.type = type;
            this.text = text;
        }
    }

    private static final String NUMBER_REGEX = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)" + "(?:[eE][+-]?[0-9]+)?";

    private static final Pattern NUMBER_PATTERN = Pattern.compile(NUMBER_REGEX);

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "\\s+"                         // Пробельные символы
                    + "|--[^\\r\\n]*"               // Комментарий до конца строки
                    + "|'(?:''|[^'])*'"             // Строка в одинарных кавычках
                    + "|" + NUMBER_REGEX           // Число
                    + "|[A-Za-z_][A-Za-z0-9_]*"     // Имя или ключевое слово
                    + "|>=|<=|<>|!="                // Двухсимвольный оператор
                    + "|[(),;*=<>]"                 // Остальные знаки
    );

    public SQLParser(DatabaseManager manager){
        this.manager = manager;
    }

    private List<Token> tokenize(String sql){
        if (sql == null) {
            throw new IllegalArgumentException("Запрос не может быть null!");
        }

        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(sql);

        int position = 0;

        while (position < sql.length()){
            matcher.region(position, sql.length());

            // Следующий элемент должен начинаться
            // строго с текущей позиции
            if (!matcher.lookingAt()) {
                throw new IllegalArgumentException("Ошибка SQL в позиции " + (position + 1) + ": неожиданный символ или незакрытая строка." );
            }

            String text = matcher.group();
            position = matcher.end();

            // Пробелы и комментарии не включаем в результат
            if (text.trim().isEmpty() || text.startsWith("--")) {
                continue;
            }

            if (text.startsWith("'")){
                // Убираем внешние кавычки
                String value = text.substring(1, text.length() - 1);

                // SQL: 'O''Brien' означает строку O'Brien
                value = value.replace("''", "'");

                tokens.add(new Token(TokenType.STRING, value));
            }

            else if (NUMBER_PATTERN.matcher(text).matches()){
                tokens.add(new Token(TokenType.NUMBER, text));
            }

            else if (text.matches("[A-Za-z_][A-Za-z0-9_]*")){
                tokens.add(new Token(TokenType.WORD, text));
            }

            else{
                tokens.add(new Token(TokenType.SYMBOL, text));
            }
        }

        return tokens;
    }

    private boolean accept(String expected){
        if (position >= tokens.size()) {
            return false;
        }

        Token token = tokens.get(position);

        // Строковый литерал 'FROM' не является ключевым словом FROM
        if (token.type == TokenType.STRING) {
            return false;
        }

        if (token.text.equalsIgnoreCase(expected)){
            position++;
            return true;
        }

        return false;
    }

    private void expect(String expected){
        if (!accept(expected)) {
            throw new IllegalArgumentException("Ожидалось: " + expected);
        }
    }

    private String readIdentifier(){
        if (position >= tokens.size()) {
            throw new IllegalArgumentException("Ожидалось имя таблицы или столбца.");
        }

        Token token = tokens.get(position);

        if (token.type != TokenType.WORD) {
            throw new IllegalArgumentException("Ожидалось имя, получено: " + token.text);
        }

        position++;
        return token.text;
    }

    private void expectEnd(){
        // Завершающая точка с запятой необязательна
        accept(";");

        if (position != tokens.size()) {
            throw new IllegalArgumentException("Лишний текст в конце запроса: "+ tokens.get(position).text);
        }
    }

    public String execute(String sql){
        tokens = tokenize(sql);
        position = 0;

        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Пустой запрос!");
        }

        if (accept("SELECT")) {
            return parseSelect();
        }
        if (accept("INSERT")) {
            return parseInsert();
        }
        if (accept("UPDATE")) {
            return parseUpdate();
        }

        if (accept("DELETE")) {
            return parseDelete();
        }

        if (accept("CREATE"))
        {
            if (accept("TABLE")) {
                return parseCreateTable();
            }

            if (accept("INDEX")) {
                return parseCreateIndex();
            }

            throw new IllegalArgumentException(
                    "После CREATE ожидалось TABLE или INDEX."
            );
        }

        if (accept("DROP"))
        {
            expect("TABLE");
            String tableName = readIdentifier();

            expectEnd();
            manager.dropTable(tableName);

            return "Таблица удалена: " + tableName;
        }

        throw new IllegalArgumentException("Пока поддерживается только SELECT или INSERT.");
    }

    private String parseSelect(){
        boolean allColumns = accept("*");
        List<String> columnNames = new ArrayList<>();

        if (!allColumns){
            columnNames.add(readIdentifier());

            while (accept(",")) {
                columnNames.add(readIdentifier());
            }
        }

        expect("FROM");
        String tableName = readIdentifier();

        List<Column> selectedColumns = new ArrayList<>();

        if (allColumns){
            selectedColumns.addAll(
                    manager.getColumns(tableName)
            );
        }
        else{
            for (String columnName : columnNames) {
                selectedColumns.add(
                        manager.getColumn(tableName, columnName)
                );
            }
        }

        Where where = parseWhere(tableName);

        // Проверяем конец запроса до выполнения поиска
        expectEnd();

        List<Record> records;

        if (where == null){
            records = new ArrayList<>(
                    manager.searchTable(tableName)
            );
        }
        else{
            records = new ArrayList<>(
                    manager.searchTable(
                            tableName,
                            where.column,
                            where.value
                    )
            );
        }

        return formatTable(selectedColumns, records);
    }

    private String parseInsert(){
        expect("INTO");
        String tableName = readIdentifier();

        List<Column> columns = new ArrayList<>();

        if (accept("(")){
            HashSet<String> usedNames = new HashSet<>();

            do{
                String columnName = readIdentifier();

                if (!usedNames.add(columnName)) {
                    throw new IllegalArgumentException(
                            "Столбец указан дважды: " + columnName
                    );
                }

                columns.add(manager.getColumn(tableName, columnName));
            }
            while (accept(","));

            expect(")");
        }
        else{
            columns.addAll(manager.getColumns(tableName));
        }

        expect("VALUES");

        List<HashMap<String, Object>> rows = new ArrayList<>();

        do{
            expect("(");
            HashMap<String, Object> values = new HashMap<>();

            for (int i = 0; i < columns.size(); i++)
            {
                if (i > 0) {
                    expect(",");
                }

                Column column = columns.get(i);
                values.put(column.getName(), readValue(column));
            }

            expect(")");
            rows.add(values);
        }
        while (accept(","));

        // Проверяем весь запрос до первой записи в файл
        expectEnd();

        for (HashMap<String, Object> row : rows) {
            manager.insertIntoTable(tableName, row);
        }

        return "Добавлено строк: " + rows.size();
    }

    private String parseUpdate(){
        String tableName = readIdentifier();

        expect("SET");
        String targetColumnName = readIdentifier();
        Column targetColumn = manager.getColumn(tableName, targetColumnName);

        expect("=");
        Object newValue = readValue(targetColumn);

        Where where = parseWhere(tableName);

        if (where == null) {
            throw new IllegalArgumentException("Для UPDATE требуется WHERE.");
        }

        expectEnd();

        manager.updateTable(
                tableName,
                where.column.getName(),
                where.value,
                targetColumnName,
                newValue
        );

        return "UPDATE выполнен.";
    }

    private String parseDelete(){
        expect("FROM");
        String tableName = readIdentifier();

        Where where = parseWhere(tableName);

        if (where == null) {
            throw new IllegalArgumentException("Для DELETE требуется WHERE.");
        }

        expectEnd();

        manager.deleteFromTable(
                tableName,
                where.column.getName(),
                where.value
        );

        return "DELETE выполнен.";
    }

    private String formatTable(List<Column> columns,List<Record> records){
        int columnCount = columns.size();

        // Первая строка — заголовки, остальные — значения
        String[][] cells = new String[records.size() + 1][columnCount];

        int[] widths = new int[columnCount];

        for (int col = 0; col < columnCount; col++){
            String columnName = columns.get(col).getName();

            cells[0][col] = columnName;
            widths[col] = columnName.length();

            for (int row = 0; row < records.size(); row++){
                Object value = records.get(row).getValues().get(columnName);

                String text;

                if (value == null) {
                    text = "NULL";
                }

                else if (columns.get(col).getType() == Column.DataType.DATE) {
                    text = LocalDate.ofEpochDay((Long) value).toString();
                }

                else {
                    text = value.toString();
                }

                // Значение не должно ломать строки вывода.
                text = text.replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t");

                cells[row + 1][col] = text;

                widths[col] = Math.max(widths[col], text.length());
            }
        }

        StringBuilder result = new StringBuilder();

        for (int row = 0; row < cells.length; row++){
            result.append("|");

            for (int col = 0; col < columnCount; col++){
                String text = cells[row][col];

                result.append(" ").append(text);

                for (int i = text.length(); i < widths[col]; i++) {
                    result.append(" ");
                }

                result.append(" |");
            }

            result.append("\n");
        }

        result.append("Строк: ").append(records.size());

        return result.toString();
    }

    private Object readValue(Column column){
        if (accept("NULL")) {
            return null;
        }

        if (position >= tokens.size()) {
            throw new IllegalArgumentException("Ожидалось значение.");
        }

        Token token = tokens.get(position++);

        try{
            switch (column.getType())
            {
                case INT:
                    if (token.type == TokenType.NUMBER && token.text.matches("[+-]?[0-9]+")) {
                        return Integer.valueOf(token.text);
                    }
                    break;

                case DOUBLE:
                    if (token.type == TokenType.NUMBER)
                    {
                        double value = Double.parseDouble(token.text);

                        if (Double.isFinite(value)) {
                            return value;
                        }
                    }
                    break;

                case STRING:
                    if (token.type == TokenType.STRING) {
                        return token.text;
                    }
                    break;

                case BOOLEAN:
                    if (token.type == TokenType.WORD
                            && (token.text.equalsIgnoreCase("TRUE")
                            || token.text.equalsIgnoreCase("FALSE"))) {
                        return Boolean.valueOf(token.text);
                    }
                    break;

                case DATE:
                    if (token.type == TokenType.STRING) {
                        return LocalDate.parse(token.text).toEpochDay();
                    }
                    break;

                default:
                    break;
            }
        }
        catch (NumberFormatException | DateTimeParseException e){
            throw new IllegalArgumentException("Некорректное значение для столбца " + column.getName() + ": " + token.text);
        }

        throw new IllegalArgumentException("Для столбца " + column.getName()+ " ожидалось значение типа " + column.getType());
    }


    private String parseCreateTable()
    {
        String tableName = readIdentifier();

        expect("(");

        ArrayList<Column> columns = new ArrayList<>();
        HashSet<String> columnNames = new HashSet<>();

        do
        {
            String columnName = readIdentifier();

            if (!columnNames.add(columnName)) {
                throw new IllegalArgumentException(
                        "Повторное имя столбца: " + columnName
                );
            }

            String typeName = readIdentifier();
            Column column;

            if (accept("("))
            {
                int size = readColumnSize();
                expect(")");
                column = new Column(columnName, typeName, size);
            }
            else
            {
                column = new Column(columnName, typeName);
            }

            if (accept("PRIMARY"))
            {
                expect("KEY");

                if (!columns.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Первичным ключом может быть только первый столбец."
                    );
                }
            }
            if (accept("REFERENCES"))
            {
                String parentTable = readIdentifier();

                expect("(");
                String parentColumn = readIdentifier();
                expect(")");

                column.setReference(parentTable, parentColumn);
            }

            columns.add(column);
        }
        while (accept(","));

        expect(")");
        expectEnd();

        if (columns.get(0).getType() != Column.DataType.INT) {
            throw new IllegalArgumentException(
                    "Первый столбец должен иметь тип INT."
            );
        }

        manager.createTable(tableName, columns);
        return "Таблица создана: " + tableName;
    }

    private int readColumnSize(){
        if (position >= tokens.size()) {
            throw new IllegalArgumentException("Ожидался размер столбца.");
        }

        Token token = tokens.get(position++);

        if (token.type != TokenType.NUMBER
                || !token.text.matches("[0-9]+")) {
            throw new IllegalArgumentException("Размер столбца должен быть положительным целым числом.");
        }

        int size;

        try {
            size = Integer.parseInt(token.text);
        }

        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Размер столбца слишком велик.");
        }

        if (size <= 0) {
            throw new IllegalArgumentException("Размер столбца должен быть больше нуля.");
        }

        return size;
    }

    private String parseCreateIndex()
    {
        String indexName = readIdentifier();

        expect("ON");
        String tableName = readIdentifier();

        expect("(");
        String columnName = readIdentifier();
        expect(")");

        expectEnd();

        manager.createIndex(indexName, tableName, columnName);
        return "Индекс создан: " + indexName;
    }
}