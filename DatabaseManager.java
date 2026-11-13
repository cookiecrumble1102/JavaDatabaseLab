import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.ObjectOutputStream;
import java.nio.file.StandardCopyOption;
import java.io.ObjectInputStream;

//　** Кирилл, добрый вечер, сразу предупреждаю, что 99% методов DataTable выбрасывают какие-то исключения
// Так что постарайся смотреть на сигнатуры и обрабатывать приходящие оттуда, а то оно поломает всю логику
// Постарался накидать все заглушки для методов, о которых вспомнил. Надеюсь, что был полезен.

// Я не знаю, что еще сюда добавить, поэтому будет анекдот:
//
// - Что будет, если в пустыне Сахара объявить социализм?
// - Первые три года ничего, а потом начнутся перебои с песком...

//Продолжу серию анектодов
//
// Забирается мужик на гору, а там сидит 120-летний монах. Мужик спрашивает:
//— Как тебе удалось дожить до такого возраста?
//— Я никогда ни с кем не спорил.
//— Невозможно!
//— Возможно, — ответил монах, и умер.



// спасите ^-^


public class DatabaseManager 
{
    // Пользовательская ошибка или предупреждение
    // Перебрасывается для дальнейшей обработки парсером/графическим интерфейсом
    public class UserException extends RuntimeException
    {
        public UserException(String message) 
        {
            super(message);
        }
    }

    // Ошибка, предупреждающая о критической неисправности в системе баз данных
    // Требует от среды управления БД экстренного завершения
    public class CriticalException extends RuntimeException
    {
        public CriticalException(String message) 
        {
            super(message);
        }
    }
    private final Map<String, DataTable> tables;
    private final Map<String, String> indexTables = new HashMap<>();
    String directoryPath;

    // По дефолту вызывается конструктор с заданной директорией работы (при запуске программы, использующей наш класс)
    public DatabaseManager(String directoryPath){
        this.tables = new HashMap<>();
        this.directoryPath = directoryPath;

        try{
            Files.createDirectories(Paths.get(directoryPath));
        }
        catch (IOException e){
            throw new CriticalException("Не удалось создать директорию базы: " + e.getMessage());
        }

        loadCatalog();
    }

    public void createIndex(String indexName,String tableName,String columnName){
        if (indexName == null || indexName.trim().isEmpty()) {
            throw new UserException("Имя индекса не может быть пустым!");
        }

        if (indexTables.containsKey(indexName)) {
            throw new UserException("Индекс уже существует: " + indexName);
        }

        DataTable table = getTable(tableName);

        try{
            table.createIndex(columnName);
        }

        catch (DataTable.DatabaseOperationException e){
            throw new UserException(e.getMessage());
        }
        catch (DataTable.DatabaseException e){
            throw new CriticalException("Ошибка создания индекса: " + e.getMessage());
        }

        indexTables.put(indexName, tableName);

        try{
            table.saveIndexes();
        }

        catch (DataTable.DatabaseException e){
            throw new CriticalException("Индекс создан в памяти, "+ "но сохранить его не удалось: "+ e.getMessage());
        }

        saveCatalog();
    }

    private DataTable getTable(String tableName)
    {
        DataTable table = this.tables.get(tableName);

        if (table == null) {
            throw new UserException("Таблица не найдена: " + tableName);
        }

        return table;
    }

    @SuppressWarnings("unchecked")
    private void loadCatalog(){
        Path catalogPath = Paths.get(directoryPath, "catalog.bin");

        // При первом запуске каталога ещё нет
        if (!Files.exists(catalogPath))
            return;


        Map<String, ArrayList<Column>> schemas;
        Map<String, String> savedIndexTables;

        try{
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(catalogPath))){

                // Читаем в том же порядке, в котором сохраняли.
                schemas = (Map<String, ArrayList<Column>>) in.readObject();

                savedIndexTables = (Map<String, String>) in.readObject();
            }

            Map<String, DataTable> loadedTables = new HashMap<>();

            for (Map.Entry<String, ArrayList<Column>> entry : schemas.entrySet()){

                String tableName = entry.getKey();
                ArrayList<Column> columns = entry.getValue();

                // Запоминаем объявленные индексы до создания DataTable:
                // его конструктор меняет признаки isIndexed.
                ArrayList<String> indexedColumns = new ArrayList<>();

                for (Column column : columns){
                    if (column.isIndexed() && !column.isKey()) {
                        indexedColumns.add(column.getName());
                    }
                }

                // Открываем существующий файл, не создаём новую таблицу.
                DataTable table = new DataTable(directoryPath, tableName, columns);

                boolean restoredMissingIndex = false;

                // Если индекс указан в каталоге, но его файл потерян,
                // строим индекс заново по данным таблицы.
                for (String columnName : indexedColumns){

                    if (!table.getColumn(columnName).isIndexed()){
                        table.createIndex(columnName);
                        restoredMissingIndex = true;
                    }
                }

                if (restoredMissingIndex) {
                    table.saveIndexes();
                }

                loadedTables.put(tableName, table);
            }

            // Обновляем состояние менеджера после успешной загрузки
            this.tables.clear();
            this.tables.putAll(loadedTables);

            this.indexTables.clear();
            this.indexTables.putAll(savedIndexTables);
        }
        catch (IOException | ClassNotFoundException | ClassCastException e){
            throw new CriticalException("Не удалось прочитать каталог базы: " + e.getMessage());
        }
        catch (DataTable.DatabaseException e){
            throw new CriticalException("Не удалось открыть таблицы базы: " + e.getMessage());
        }
    }

    private void saveCatalog(){
        Map<String, ArrayList<Column>> schemas = new HashMap<>();

        for (Map.Entry<String, DataTable> entry : tables.entrySet()){
            String tableName = entry.getKey();
            DataTable table = entry.getValue();

            schemas.put(tableName, table.getColumns());
        }

        Path catalogPath = Paths.get(directoryPath, "catalog.bin");

        Path tempPath = Paths.get(directoryPath, "catalog.bin.tmp");

        try{
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tempPath))){
                // Порядок важен: загрузчик прочитает в том же порядке.
                out.writeObject(schemas);
                out.writeObject(indexTables);
            }

            Files.move(
                    tempPath,
                    catalogPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
        catch (IOException e){
            throw new CriticalException("Не удалось сохранить каталог базы: "+ e.getMessage());
        }
    }

    public Column getColumn(String tableName, String columnName){
        DataTable table = getTable(tableName);

        try{
            return table.getColumn(columnName);
        }

        catch (DataTable.DatabaseOperationException e){
            throw new UserException(e.getMessage());
        }
    }

    // CREATE TABLE
    public void createTable(String dbName, ArrayList<Column> columns)
    {
        if (columns == null || columns.isEmpty()) {
            throw new UserException("Таблица должна содержать хотя бы один столбец.");
        }

        long recordSize = 0;
        long maxRecordSize = 1024L * 1024L;

        for (Column column : columns)
        {
            recordSize += (long) column.getSize() + 1L;

            if (recordSize > maxRecordSize) {
                throw new UserException( "Размер одной записи превышает 1 МиБ.");
            }
        }

        try
        {
            for (Column column : columns){
                if (column.getReferencedTable() == null)
                    continue;


                Column parentColumn = getColumn(
                        column.getReferencedTable(),
                        column.getReferencedColumn()
                );

                if (!parentColumn.isKey() || column.getType() != parentColumn.getType()) {
                    throw new UserException("Внешний ключ " + column.getName()+ " должен ссылаться на первичный ключ того же типа.");
                }
            }


            String dbFilePath = directoryPath + File.separator + dbName + ".db";
            String indexFilePath = directoryPath + File.separator + dbName + "_" + columns.get(0).getName() + ".idx";
            File dbFile = new File(dbFilePath);
            File indexFile = new File(indexFilePath);


            // Если нет файлов БД, то создаем их, иначе БД будет считать, что файлы уже есть
            if (tables.containsKey(dbName) || dbFile.exists()) {
                throw new UserException("Таблица уже существует: " + dbName);
            }

            if (indexFile.exists()) {
                throw new UserException("Файл индекса уже существует: "+ indexFile.getName());
            }

            if (!dbFile.createNewFile()) {
                throw new IOException("Не удалось создать файл таблицы: " + dbName);
            }

            if (!indexFile.createNewFile()) {
                throw new IOException("Не удалось создать файл индекса: "+ indexFile.getName());
            }

            // Здесь желательно сделать индексацию по тем полям, для которых уже есть файл индекса (загрузить в оперативку :3)
            // Т.е цикл по колонкам и проверка на создание файла, если есть -> делать loadIndex(), иначе скип

            DataTable table = new DataTable(this.directoryPath, dbName, columns);
            this.tables.put(dbName, table);
            saveCatalog();

        }
        catch (IllegalArgumentException e)
        {
            throw new UserException("Не удалось создать таблицу " + dbName + "!" + e.getLocalizedMessage());
        }
        catch (IOException e)
        {
            throw new CriticalException(e.getLocalizedMessage());
        }
    }

    // DROP TABLE
    public void dropTable(String tableName){
        DataTable table = getTable(tableName);
        Path directory = Paths.get(this.directoryPath);

        for (Map.Entry<String, DataTable> entry : tables.entrySet()){
            for (Column column : entry.getValue().getColumns()){
                if (tableName.equals(column.getReferencedTable())) {
                    throw new UserException("Таблицу нельзя удалить: на неё ссылается " + entry.getKey() + "." + column.getName());
                }
            }
        }

        try{
            // Удаляем файлы индексов этой таблицы
            for (Column column : table.getColumns()){
                String indexName = tableName + "." + column.getName() + ".idx";

                Files.deleteIfExists(directory.resolve(indexName));

                // Удаляем временный файл, если он остался
                Files.deleteIfExists(directory.resolve(indexName + ".tmp"));
            }

            // Файл данных удаляем после индексов
            Files.delete(directory.resolve(tableName + ".db"));
        }
        catch (IOException e){
            throw new CriticalException("Не удалось полностью удалить таблицу "+ tableName + ": " + e.getMessage());
        }

        // Убираем таблицу из памяти после удаления файлов
        this.tables.remove(tableName);

        indexTables.entrySet().removeIf(entry -> entry.getValue().equals(tableName));

        saveCatalog();
    }


    // SEARCH
    // В качестве value передается либо значение, которое нужно найти, либо объект класса Range
    public HashSet<Record> searchTable(String tableName, Column column, Object value){
        DataTable table = getTable(tableName);

        try{
            return table.searchLine(column, value);
        }

        catch (DataTable.DatabaseOperationException e){
            throw new UserException(e.getMessage());
        }

        catch (DataTable.DatabaseException e){
            throw new CriticalException("Ошибка поиска в таблице "+ tableName + ": " + e.getMessage());
        }
    }

    public HashSet<Record> searchTable(String tableName){
        DataTable table = getTable(tableName);

        try{
            return table.selectAll();
        }

        catch (DataTable.DatabaseException e){
            throw new CriticalException("Ошибка чтения таблицы "+ tableName + ": " + e.getMessage());
        }
    }

    // INSERT INTO
    public void insertIntoTable(String tableName, HashMap<String, Object> values){
        DataTable table = getTable(tableName);
        if (values != null){
            for (Column column : table.getColumns()) {
                checkForeignKey(column, values.get(column.getName()));
            }
        }

        // Сначала вставляем запись
        try{
            table.insertLine(values);
        }

        catch (DataTable.DatabaseOperationException e){
            throw new UserException(e.getMessage());
        }

        catch (DataTable.DatabaseException e){
            throw new CriticalException("Ошибка вставки в таблицу "+ tableName + ": " + e.getMessage());
        }

        // После успешной вставки сохраняем индексы
        try{
            table.saveIndexes();
        }
        catch (DataTable.DatabaseException e){
            throw new CriticalException("Запись добавлена в таблицу "+ tableName+ ", но сохранить индексы не удалось: "+ e.getMessage());
        }
    }

    // UPDATE
    public void updateTable(String tableName,String searchColumnName,Object searchValue,String targetColumnName,Object newValue){
        DataTable table = getTable(tableName);

        Column searchColumn = getColumn(tableName, searchColumnName);

        Column targetColumn = getColumn(tableName, targetColumnName);

        if (targetColumn.getReferencedTable() != null && newValue != null && !searchTable(tableName, searchColumn, searchValue).isEmpty()) {
            checkForeignKey(targetColumn, newValue);
        }

        try{
            table.updateLine(searchColumn, searchValue, targetColumn, newValue);
        }
        catch (DataTable.DatabaseOperationException e){
            throw new UserException(e.getMessage());
        }
        catch (DataTable.DatabaseException e){
            throw new CriticalException("Ошибка обновления таблицы "+ tableName + ": " + e.getMessage());
        }

        try{
            table.saveIndexes();
        }
        catch (DataTable.DatabaseException e){
            throw new CriticalException("Обновление выполнено в таблице "+ tableName+ ", но сохранить индексы не удалось: "+ e.getMessage());
        }
    }

    // DELETE
    public void deleteFromTable(String tableName,String columnName,Object value){
        DataTable table = getTable(tableName);
        Column column = getColumn(tableName, columnName);

        checkDeleteReferences(tableName, table, column, value);

        try{
            table.deleteLine(column, value);
        }
        catch (DataTable.DatabaseOperationException e){
            throw new UserException(e.getMessage());
        }

        catch (DataTable.DatabaseException e){
            throw new CriticalException("Ошибка удаления из таблицы "+ tableName + ": " + e.getMessage());
        }

        try{
            table.saveIndexes();
        }
        catch (DataTable.DatabaseException e){
            throw new CriticalException("Удаление выполнено в таблице "+ tableName+ ", но сохранить индексы не удалось: "+ e.getMessage());
        }
    }

    public ArrayList<Column> getColumns(String tableName){
        return getTable(tableName).getColumns();
    }

    private void checkForeignKey(Column column, Object value){
        String parentTable = column.getReferencedTable();

        if (parentTable == null || value == null)
            return;


        Column parentColumn = getColumn(parentTable, column.getReferencedColumn());

        if (searchTable(parentTable, parentColumn, value).isEmpty()) {
            throw new UserException("Для " + column.getName() + " = " + value+ " нет записи в таблице " + parentTable + ".");
        }
    }

    private void checkDeleteReferences(String tableName,DataTable table,Column searchColumn,Object searchValue){
        String keyName = table.getColumns().get(0).getName();
        HashSet<Object> keysToDelete = new HashSet<>();

        for (Record record : searchTable(tableName, searchColumn, searchValue)) {
            keysToDelete.add(record.getValues().get(keyName));
        }

        if (keysToDelete.isEmpty())
            return;

        for (Map.Entry<String, DataTable> entry : tables.entrySet()){
            String childTableName = entry.getKey();

            for (Column childColumn : entry.getValue().getColumns()){
                if (!tableName.equals(childColumn.getReferencedTable()))
                    continue;


                for (Record child : searchTable(childTableName)){
                    Object reference = child.getValues().get(childColumn.getName());

                    if (keysToDelete.contains(reference)) {
                        throw new UserException("Удаление невозможно: на запись ссылается "+ childTableName + "." + childColumn.getName());
                    }
                }
            }
        }
    }
    // CREATE INDEX
    // 1) вызывает приватную FILL INDEX
    // 2) затем запускает loadIndex для дбшки

    // private FILL INDEX
    // создает и заполняет индексный файл
    

}