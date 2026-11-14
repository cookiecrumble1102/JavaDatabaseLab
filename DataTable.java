// Класс, в котором хранится информация о конкретной базе данных
// Через него получаем доступ к хранимой информации, тут происходит все хеширование и здесь же выполняется основной функционал

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

// ** По индексации
// Менеджер создает файл индекса по запросу, затем вызывает fillIndexFile() -> loadIndexFile()
// При завершении программы автоматически вызывается fillIndexFile() для всех индексируемых полей, чтобы отразить изменения произошедшие в базе


// Важные комменты пометил **, чтобы не забывать. От них зависит жизнеспособность этого кода, т.к в них прописаны нюансы, забыв которые, можно все безвозвратно положить в будущем.
public class DataTable
{
    // Кастомные ошибки

    // Общая ошибка в базе данных
    public class DatabaseException extends RuntimeException
    {
        public DatabaseException(String message) 
        {
            super(message);
        }
    }


    // Ошибка операции в базе данных (вставки, удаления и т.д)
    // Не ведет к аварийному завершению программы и позволяет пользователю самостоятельно решить проблему 
    // - невыполнимый запрос
    // - нарушение уникальности первичного ключа 
    // - опечатка на стороне пользователя
    // - отсутствие записей 
    public class DatabaseOperationException extends DatabaseException
    {
        public DatabaseOperationException(String message) 
        {
            super(message);
        }
    }

    // Ошибка обращения к файлу индексов по ключевому полю
    // !! Cигнализирует о необходимости совершить перезапись индексного файла для данной БД
    public class PrimaryKeyIntegrityException extends FileIntegrityException
    {
        public PrimaryKeyIntegrityException(String message) 
        {
            super(message);
        }
    }

    // Ошибка обращения к файлу, сигнализирует о нарушении целостности файлов БД
    // !! Cигнализирует о необходимости аварийно завершить работу программы и предупреждает пользователя о возможном повреждении данных
    public class FileIntegrityException extends DatabaseException
    {
        public FileIntegrityException(String message) 
        {
            super(message);
        }
    }


    // Ошибка индексации по ключевому полю
    // !! Cигнализирует о ключевой ошибке и требует аварийного завершения всей программы на уровне менеджера баз данных
    public class PrimaryKeyIndexException extends IndexException
    {
        public PrimaryKeyIndexException(String message) 
        {
            super(message);
        }
    }

    // Ошибка индексации по полю
    // Сигнализирует о том, что индексация не была проведена. Не вызывает аварийного завершения работы.
    public class IndexException extends DatabaseException
    {
        public IndexException(String message) 
        {
            super(message);
        }
    }


    // Константы

    // ** В файле перед каждым полем (кроме ключевого поля) лежит бит, показывающий наличие или отсутствие этого поля в записи (где false - отсутствие)**
    private final int DBRECORDSIZE;
    private String KEYFIELDNAME = "";

    // Шаблон полей
    private ArrayList<Column> columns;

    // Файлы
    
    // Файлы ДБшки
    private final String DBFILEPATH;

    // Хэш-объекты
    private HashMap<String, Object> hashes; 
    // Храним [имя: хеш-таблицу со смещениями в файле для получения нужного ключа]
    // Object здесь стоит, т.к у нас будут использоваться как обычные HashMap, так и TreeMap (для чисел и обработки условий WHERE)


    // Создаем базу данных на основе пути к директории, имени и упорядоченного набора объектов Column
    public DataTable(String directoryPath, String name, ArrayList<Column> columns) throws IllegalArgumentException
    {
        // Файлы предварительно созданы в Database Manager и их валидность никак не проверяется этим классом, т.к он не отвечает за создание файлов
        // В качестве исключения делается проверка на существование файлов индексации для конкретных полей, но это прямая необходимость
        
        DBFILEPATH = directoryPath + File.separator + name + ".db";

        // ** Будем программно докидывать в начало поле Boolean isDeleted, чтобы делать Soft-Delete **
        // Предполагаем, что ключевое поле будет идти первым


        // Здесь предполагается, что парсер заранее проверил, что более одной колонки и запись не будет пустой
        if (columns.size() == 0) {throw new IllegalArgumentException("Аттрибут столбцов передан пустым");}

        // Смотрим, сколько будет занимать одна запись в БД
        this.columns = new ArrayList<Column>();
        this.hashes = new HashMap<>(); // ИСПРАВЛЕНО: добавлена инициализация мапы, чтобы не было NullPointerException


        // Начинаем с нуля, т.к у нас isDeleted в начале по сути является полем для определения NULL для ключевого
        int size = 0;
        for (Column item : columns)
        {
            size += item.getSize() + 1;

            // Запись в шаблон колонок
            this.columns.add(item);
        }

        // Один раз перезаписываем final-поле, теперь работаем с ним
        DBRECORDSIZE = size;

        validateDataFile();

        // Задаем первое поле ключевым
        this.columns.get(0).setKey(true);

        rebuildPrimaryKeyIndex();


        for (Column column : this.columns){
            // Первичный индекс уже восстановлен из файла данных.
            if (column.isKey()) {
                continue;
            }

            column.setIndexed(false);

            String indexPath = directoryPath + File.separator + name + "." + column.getName() + ".idx";

            File indexFile = new File(indexPath);

            if (!indexFile.exists()) {
                continue;
            }

            try{
                loadNonKeyIndex(column, indexPath);
            }
            catch (DatabaseException e){
                this.hashes.remove(column.getName());
                column.setIndexed(false);

                System.err.println("Не удалось загрузить индекс столбца "+ column.getName()+ ": " + e.getMessage());
            }
            verifyNonKeyIndex(column);
        }
    }

    // Загружаем индексные файлы
    // Отдельно загружаем индексный файл для ключевого поля

    // Методы для индексации

    // Загрузка в оперативку из индексных файлов
    // Для ключевого поля
    // Формат хранения в файле [ID] [Long смещение]
    public void loadKeyIndex(Column column, String filePath) 
    {
        try (RandomAccessFile rf = new RandomAccessFile(filePath, "rw"))
        {
            HashMap<Integer, Long> thisMap = new HashMap<>();

            Long len = rf.length();
            Long current = 0L;
            while (current < len)
            {
                rf.seek(current);
                Integer key = rf.readInt();
                Long value = rf.readLong();
                thisMap.put(key, value);
                current += column.getSize() + 8;
            }
            this.hashes.put(column.getName(), thisMap);
            column.setIndexed(true);
        }
        catch (IOException e)
        {
            throw new DatabaseException(e.getLocalizedMessage());
        }
    }

    // Для неключевого поля
    // Формат хранения в файле [Значение] [INT кол-во записей] [Набор Long смещений]
    public void loadNonKeyIndex(Column column, String filePath) 
    {
        try (RandomAccessFile rf = new RandomAccessFile(filePath, "r"))
        {
            HashMap<Object, HashSet<Long>> thisMap = new HashMap<>();

            Long len = rf.length();
            Long current = 0L;
            while (current < len)
            {
                rf.seek(current);
                Object key = null;
                switch (column.getType())
                {
                    case INT:
                        key = (Integer) rf.readInt();
                        break;

                    case DOUBLE:
                        key = (Double) rf.readDouble();
                        break;

                    case BOOLEAN:
                        key = (Boolean) rf.readBoolean();
                        break;

                    case DATE:
                        key = (Long) rf.readLong();
                        break;

                    case STRING:
                        byte[] nameBuffer = new byte[column.getSize()];
                        rf.readFully(nameBuffer);

                        int end = nameBuffer.length;

                        // Убираем только нулевые байты заполнения в конце.
                        while (end > 0 && nameBuffer[end - 1] == 0) {end--;}

                        String name = new String(nameBuffer,0, end, StandardCharsets.UTF_8);
                        key = name;
                        break;

                    default:
                        throw new IndexException("Неподдерживаемый тип столбца в индексе: "+ column.getType());
                }

                int amount = rf.readInt();

                long remainingBytes = rf.length() - rf.getFilePointer();

                // Одно смещение Long занимает 8 байт
                if (amount < 0 || amount > remainingBytes / 8) {
                    throw new IndexException("Повреждён индекс столбца "+ column.getName()+ ": некорректное количество смещений — "+ amount);
                }

                if (key != null)
                {
                    HashSet<Long> offsetSet = new HashSet<>();
                    for (int i = 0; i < amount; i++)
                    {
                        offsetSet.add(rf.readLong());
                    }
                    thisMap.put(key, offsetSet); // ИСПРАВЛЕНО: теперь сохраняется весь сет смещений, а не затирается последним
                }

                current = rf.getFilePointer();

            }
            this.hashes.put(column.getName(), thisMap);
            column.setIndexed(true);
        }
        catch (IOException e)
        {
            throw new DatabaseException(e.getLocalizedMessage());
        }
    }

    // Изменения хэш-таблицы в оперативке при обновлении данных

    // Добавление хэшированных записей
    private void addToIndex(HashMap<String, Object> values, Long offset)
    {
        for (Column column : this.columns)
        {
            if (column.getType() == Column.DataType.NONE) {
                continue;
            }

            String columnName = column.getName();
            Object value = values.get(columnName);

            addColumnToHash(column, value, offset);
        }
    }

    @SuppressWarnings("unchecked")
    private void addColumnToHash(Column column, Object value, Long offset)
    {
        if (!column.isIndexed() || value == null) {
            return;
        }

        String columnName = column.getName();

        if (column.isKey()) {
            Map<Integer, Long> keyMap = (Map<Integer, Long>) this.hashes.get(columnName);

            if (keyMap != null) {
                keyMap.put((Integer) value, offset);
            }
        }
        else {
            Map<Object, HashSet<Long>> indexMap = (Map<Object, HashSet<Long>>) this.hashes.get(columnName);

            if (indexMap != null) {
                indexMap.computeIfAbsent(value, k -> new HashSet<>()).add(offset);
            }
        }
    }

    // Удаление хэшированных записей
    private void removeFromIndex(Long offset)
    {
        Record rec = readLine(offset);

        if (rec == null) {
            return;
        }

        HashMap<String, Object> values = rec.getValues();

        for (Column column : this.columns)
        {
            if (column.getType() == Column.DataType.NONE) {
                continue;
            }

            String columnName = column.getName();
            Object value = values.get(columnName);

            removeColumnFromHash(column, value, offset);
        }
    }

    // Проверка на корректность данных
    private void validateValues(HashMap<String, Object> values)
    {
        if (values == null) {
            throw new DatabaseOperationException("Не переданы значения записи!");
        }

        // Проверяем, что переданы только существующие столбцы.
        for (String name : values.keySet()){
            boolean found = false;

            for (Column column : this.columns){
                if (column.getName().equals(name)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                throw new DatabaseOperationException("Неизвестный столбец: " + name);
            }
        }

        // Проверяем значения всех столбцов.
        for (Column column : this.columns){
            Object value = values.get(column.getName());

            if (value == null)
            {
                if (column.isKey()) {
                    throw new DatabaseOperationException(
                            "Первичный ключ не может быть NULL!"
                    );
                }

                continue;
            }

            boolean valid;

            switch (column.getType()){
                case INT:
                    valid = value instanceof Integer;
                    break;

                case DOUBLE:
                    valid = value instanceof Double
                            && Double.isFinite((Double) value);
                    break;

                case BOOLEAN:
                    valid = value instanceof Boolean;
                    break;

                case DATE:
                    valid = value instanceof Long;
                    break;

                case STRING:
                    valid = value instanceof String
                            && ((String) value).indexOf('\0') < 0
                            && ((String) value)
                            .getBytes(StandardCharsets.UTF_8).length
                            <= column.getSize();
                    break;

                default:
                    valid = false;
            }

            if (!valid) {
                throw new DatabaseOperationException("Неверный тип или размер значения для столбца: " + column.getName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void removeColumnFromHash(Column column, Object value, Long offset)
    {
        if (!column.isIndexed() || value == null) {
            return;
        }

        String columnName = column.getName();

        if (column.isKey()) {
            Map<Integer, Long> keyMap = (Map<Integer, Long>) this.hashes.get(columnName);

            if (keyMap != null) {
                keyMap.remove((Integer) value);
            }
        }
        else {
            Map<Object, HashSet<Long>> indexMap = (Map<Object, HashSet<Long>>) this.hashes.get(columnName);

            if (indexMap != null) {
                HashSet<Long> offsets = indexMap.get(value);

                if (offsets != null) {
                    offsets.remove(offset);

                    if (offsets.isEmpty()) {
                        indexMap.remove(value);
                    }
                }
            }
        }
    }

    // Метод сохранения индексов
    @SuppressWarnings("unchecked")
    public void saveIndexes() {
        String basePath = DBFILEPATH.substring(0,DBFILEPATH.length() - 3);

        for (Column column : this.columns){
            if (!column.isIndexed())
                continue;


            Object index = this.hashes.get(column.getName());

            if (index == null) {
                throw new IndexException("Не найден индекс столбца: " + column.getName());
            }

            Path indexPath = Paths.get(basePath + "." + column.getName() + ".idx");

            Path tempPath = Paths.get(indexPath.toString() + ".tmp");

            try{
                // Основной индекс пока не трогаем
                try (RandomAccessFile rf =new RandomAccessFile(tempPath.toFile(), "rw")){
                    rf.setLength(0);

                    if (column.isKey()){
                        Map<Integer, Long> keyMap = (Map<Integer, Long>) index;

                        for (Map.Entry<Integer, Long> entry : keyMap.entrySet()){
                            rf.writeInt(entry.getKey());
                            rf.writeLong(entry.getValue());
                        }
                    }
                    else{
                        Map<Object, HashSet<Long>> indexMap = (Map<Object, HashSet<Long>>) index;

                        for (Map.Entry<Object, HashSet<Long>> entry : indexMap.entrySet()){
                            writeColumn(column, entry.getKey(), rf);

                            rf.writeInt(entry.getValue().size());

                            for (Long offset : entry.getValue()) {
                                rf.writeLong(offset);
                            }
                        }
                    }

                    // Сохраняем содержимое временного файла на диск
                    rf.getFD().sync();
                }

                // Файл уже закрыт — заменяем основной индекс
                Files.move(
                        tempPath,
                        indexPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
            catch (IOException e)
            {
                throw new FileIntegrityException(
                        "Не удалось сохранить индекс "
                                + column.getName()
                                + ": " + e.getMessage()
                );
            }
        }
    }

    // Вставка записи
    @SuppressWarnings("unchecked")
    public void insertLine(HashMap<String, Object> values) throws DatabaseOperationException
    {
        validateValues(values);

        // Заранее проверяем крайние случаи, при возникновении которых мы просто не можем выполнять операцию вставки
        if (values.get(KEYFIELDNAME) == null)
        {
            throw new DatabaseOperationException("В запросе не обнаружено ключевого поля " + KEYFIELDNAME + " для данной базы данных!");
        }

        Map<Integer, Long> keyMap = (Map<Integer, Long>) hashes.get(KEYFIELDNAME);
        if (keyMap != null && keyMap.containsKey(values.get(KEYFIELDNAME)))
        {
            throw new DatabaseOperationException("В базе данных уже находится объект с таким значением ключевого аттрибута " + KEYFIELDNAME + "!" +  " Попробуйте применить изменение строки!");
        }

        try (RandomAccessFile rf = new RandomAccessFile(this.DBFILEPATH, "rw"))
        {
            Long len = rf.length();
            rf.seek(len);


            for (Column column : this.columns)
            {
                if (column.getType() == Column.DataType.NONE) {continue;}
                String columnName = column.getName();
                
                // Если не нашли такого поля, то делаем маркер на NULL, и заполняем остальные байты мусором (вполне возможно, что пользователь потом отредактирует запись, так что не просто скипаем байты)
                Object searchResult = values.get(columnName);
                if (searchResult == null)
                {
                    rf.writeBoolean(false);
                    rf.write(new byte[column.getSize()]);
                }
                // Если поле найдено, то производим обычную запись
                else
                {
                    // Для ключевого поля дополнительный байт будет играть роль isDeleted (существование всей записи)
                    rf.writeBoolean(true);
                    try 
                    {
                        writeColumn(column, searchResult, rf);
                    }
                    catch (DatabaseException e) 
                    {
                        rf.setLength(len);
                        throw new DatabaseOperationException("Ошибка создания записи! " + e.getLocalizedMessage());
                    }
                }
            }

            // Если все успешно записалось - > добавляем в таблицу с хешами
            addToIndex(values, len);
        }
        catch (FileNotFoundException e)
        {
            throw new PrimaryKeyIntegrityException("Ошибка при чтении и записи в индексный файл по ключевому полю: " + KEYFIELDNAME + "! " + "Пересоздайте файл .idx для ключевого поля!");
        }
        catch (IOException e)
        {
            throw new DatabaseOperationException(e.getLocalizedMessage());
        }
        
    }

    // Вспомогательный метод для записи колонки
    private void writeColumn(Column item, Object value, RandomAccessFile rf) throws DatabaseException
    {
        try
        {
            switch (item.getType())
            {
                case INT:
                    rf.writeInt((Integer)value);
                    break;

                case DOUBLE:
                    rf.writeDouble((Double) value);
                    break;

                case BOOLEAN:
                    rf.writeBoolean((Boolean) value);
                    break;

                case DATE:
                    rf.writeLong((Long) value);
                    break;

                case STRING:
                    String text = (String) value;

                    // Нулевой символ используется для заполнения пустого места.
                    if (text.indexOf('\0') >= 0) {
                        throw new DatabaseOperationException("Строка не должна содержать нулевой символ!");
                    }

                    byte[] stringBytes = text.getBytes(StandardCharsets.UTF_8);

                    if (stringBytes.length > item.getSize()) {
                        throw new DatabaseOperationException("Строка слишком длинная для столбца " + item.getName() + ": допустимо " + item.getSize() + " байт, получено " + stringBytes.length);
                    }

                    byte[] stringBuffer = new byte[item.getSize()];

                    System.arraycopy(stringBytes,0,stringBuffer,0,stringBytes.length);

                    rf.write(stringBuffer);
                    break;

                default: break;
            }
        }
        catch (IOException e)
        {
            throw new DatabaseException("Ошибка при записи в файл! " + e.getLocalizedMessage());
        }

    }

    // Обновление строк
    public void updateLine(
            Column searchColumn,
            Object searchValue,
            Column targetColumn,
            Object newValue)
    {
        // Находим реальные столбцы таблицы.
        Column actualSearchColumn = null;
        Column actualTargetColumn = null;

        // Смещение изменяемого поля относительно начала строки.
        long targetColumnOffset = 0;
        long currentColumnOffset = 0;

        for (Column column : this.columns)
        {
            if (column.getName().equals(searchColumn.getName())) {
                actualSearchColumn = column;
            }

            if (column.getName().equals(targetColumn.getName()))
            {
                actualTargetColumn = column;
                targetColumnOffset = currentColumnOffset;
            }

            // Один байт флага + байты значения.
            currentColumnOffset += column.getSize() + 1;
        }

        if (actualSearchColumn == null || actualTargetColumn == null) {
            throw new DatabaseOperationException(
                    "Столбец поиска или изменения не найден!"
            );
        }

        if (actualTargetColumn.isKey()) {
            throw new DatabaseOperationException(
                    "Изменение первичного ключа пока не поддерживается!"
            );
        }

        // Проверяем значение до изменения файла.
        if (newValue != null)
        {
            boolean valid;

            switch (actualTargetColumn.getType())
            {
                case INT:
                    valid = newValue instanceof Integer;
                    break;

                case DOUBLE:
                    valid = newValue instanceof Double
                            && Double.isFinite((Double) newValue);
                    break;

                case BOOLEAN:
                    valid = newValue instanceof Boolean;
                    break;

                case DATE:
                    valid = newValue instanceof Long;
                    break;

                case STRING:
                    valid = newValue instanceof String
                            && ((String) newValue).indexOf('\0') < 0
                            && ((String) newValue)
                            .getBytes(StandardCharsets.UTF_8).length
                            <= actualTargetColumn.getSize();
                    break;

                default:
                    valid = false;
            }

            if (!valid) {
                throw new DatabaseOperationException(
                        "Неверный тип или размер нового значения!"
                );
            }
        }

        try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "rw"))
        {
            long fileLength = rf.length();

            for (long rowOffset = 0;
                 rowOffset < fileLength;
                 rowOffset += DBRECORDSIZE)
            {
                // Пропускаем удалённые строки.
                rf.seek(rowOffset);

                if (!rf.readBoolean()) {
                    continue;
                }

                Record record = readLine(rowOffset);

                Object currentSearchValue = record.getValues()
                        .get(actualSearchColumn.getName());

                // Обычное SQL-сравнение "=" не выбирает NULL.
                if (currentSearchValue == null || searchValue == null) {
                    continue;
                }

                boolean matches;

                if (searchValue instanceof Range){
                    Range<?> range = (Range<?>) searchValue;

                    matches = currentSearchValue instanceof Number
                            && range.checkCondition((Number) currentSearchValue);
                }
                else{
                    matches = currentSearchValue.equals(searchValue);
                }

                if (!matches) {
                    continue;
                }

                Object oldValue = record.getValues()
                        .get(actualTargetColumn.getName());

                // Переходим к флагу изменяемого поля.
                rf.seek(rowOffset + targetColumnOffset);

                rf.writeBoolean(newValue != null);

                if (newValue == null) {
                    rf.write(new byte[actualTargetColumn.getSize()]);
                } else {
                    writeColumn(actualTargetColumn, newValue, rf);
                }

                // В индексах всегда используем начало всей строки.
                removeColumnFromHash(
                        actualTargetColumn,
                        oldValue,
                        rowOffset
                );

                addColumnToHash(
                        actualTargetColumn,
                        newValue,
                        rowOffset
                );
            }
        }
        catch (IOException e)
        {
            throw new DatabaseOperationException(
                    "Не удалось обновить записи: " + e.getMessage()
            );
        }
    }

    // Удаление строк
    public void deleteLine(Column column, Object value)
    {
        HashSet<Long> offsets = new HashSet<>(getLineOffset(column, value));

        // Ничего не найдено — удалять нечего
        if (offsets.isEmpty())
            return;

        try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "rw")){
            for (Long offset : offsets){
                // Читаем ещё существующую строку
                // и удаляем её смещения из индексов
                removeFromIndex(offset);

                // Затем помечаем саму строку удалённой
                rf.seek(offset);
                rf.writeBoolean(false);
            }
        }
        catch (IOException e){
            throw new FileIntegrityException("Ошибка записи при удалении: " + e.getMessage());
        }
    }


    // Функции поиска

    // Линейный поиск по базе данных
    // Не делаем публичным, т.к всегда стараемся проводить индексируемый поиск, а не линейным
    private HashSet<Record> searchLinear(Column column, Object value) {
        HashSet<Record> records = new HashSet<>();

        try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "r"))        {
            long fileLength = rf.length();

            for (long offset = 0; offset < fileLength; offset += DBRECORDSIZE)            {
                rf.seek(offset);

                // Пропускаем удалённые строки.
                if (!rf.readBoolean())
                    continue;

                Record record = readLine(offset);

                // В отличие от getValue(), возвращает null без исключения.
                Object actualValue = record.getValues().get(column.getName());

                if (actualValue == null || value == null)
                    continue;


                boolean matches;

                if (value instanceof Range){
                    if (!(actualValue instanceof Number)){
                        throw new DatabaseOperationException("Числовой диапазон нельзя применить к столбцу "+ column.getName());
                    }

                    Range<?> range = (Range<?>) value;

                    matches = range.checkCondition(
                            (Number) actualValue
                    );
                }
                else{
                    matches = actualValue.equals(value);
                }

                if (matches) {
                    records.add(record);
                }
            }
        }
        catch (IOException e){
            throw new FileIntegrityException("Ошибка чтения таблицы: " + e.getMessage());
        }

        return records;
    }
    


    // Индексированные функции поиска

    // Общая функция поиска в базе данных
    public HashSet<Record> searchLine(Column column, Object value)
    {
        // Проверяем запрос диапазона.
        if (value instanceof Range){
            if (column.getType() != Column.DataType.INT
                    && column.getType() != Column.DataType.DOUBLE) {
                throw new DatabaseOperationException(
                        "Числовой диапазон поддерживается только для INT и DOUBLE!"
                );
            }
        }
        else if (column.isKey() && value != null && !(value instanceof Integer)){
            throw new DatabaseOperationException("Значение первичного ключа должно быть Integer!");
        }

        // Если индекса нет — просматриваем строки файла.
        if (!column.isIndexed()) {
            return searchLinear(column, value);
        }

        // Если индекс есть — получаем смещения подходящих строк.
        HashSet<Long> offsets = getLineOffset(column, value);
        HashSet<Record> result = new HashSet<>();

        for (Long offset : offsets){
            Record record = readLine(offset);

            if (record != null) {
                result.add(record);
            }
        }

        return result;
    }

    // Метод для поиска по ключевому значению
    private Record searchByKeyValue(int value) throws DatabaseOperationException
    {
        try
        {
            Long offset = getLineOffset((Integer) value);
            return readLine(offset);
        }
        catch (DatabaseException e)
        {
            throw new DatabaseOperationException(e.getLocalizedMessage());
        }
    }

    // Приватный метод для поиска по неключевому значению
    private HashSet<Record> searchByNonKeyValue(Column item, Object value) throws DatabaseOperationException
    {
        HashSet<Record> container = new HashSet<>();
        // Проверяем на наличие хэширования для столбца
        if (item.isIndexed())
        {
            // Считываем оффсеты
            try
            {
                HashSet<Long> offsets = getLineOffset(item, value);
                for (Long offset : offsets)
                {
                    Record rec = readLine(offset);
                    if (rec != null) container.add(rec);
                }
                return container;
            }
            catch (DatabaseException e)
            {
                throw new DatabaseOperationException(e.getLocalizedMessage());
            }
        }
        
        // Иначе запускаем линейный поиск
        else
        {
            return searchLinear(item, value);
        }
    }


    // Приватный метод для получения смещения для ключевого поля
    @SuppressWarnings("unchecked")
    private Long getLineOffset(int value) throws DatabaseException, PrimaryKeyIndexException
    {
        Map<Integer, Long> thisMap = (Map<Integer, Long>) this.hashes.get(KEYFIELDNAME);

        if (thisMap == null) {throw new PrimaryKeyIndexException("Ошибка поиска! Поле ключевого аттрибута " + KEYFIELDNAME +  " не проиндексировано!");}

        if (thisMap.containsKey(value))
        {
            return thisMap.get(value);
        }
        else
        {
            throw new DatabaseException("Такое значение по аттрибуту " + KEYFIELDNAME + " не найдено записей!");
        }
    }


    // Приватный метод для получения смещения через хэш-таблицу для неключевого поля (не по условиям)
    @SuppressWarnings("unchecked")
    private HashSet<Long> getLineOffset(Column column, Object value)
    {
        if (value instanceof Range) {
            return getLineOffset(column, (Range<?>) value);
        }

        HashSet<Long> result = new HashSet<>();
        String columnName = column.getName();

        // Обычное сравнение "=" не выбирает NULL.
        if (value == null) {
            return result;
        }

        // Поиск по первичному ключу.
        if (column.isKey()){
            Map<Integer, Long> keyMap =
                    (Map<Integer, Long>) this.hashes.get(columnName);

            if (keyMap == null) {
                throw new PrimaryKeyIndexException(
                        "Не найден индекс первичного ключа: " + columnName
                );
            }

            if (!(value instanceof Integer)) {
                throw new DatabaseOperationException(
                        "Значение первичного ключа должно быть Integer!"
                );
            }

            Long offset = keyMap.get((Integer) value);

            if (offset != null) {
                result.add(offset);
            }

            return result;
        }

        // Поиск по обычному индексированному столбцу.
        if (column.isIndexed()){
            Map<Object, HashSet<Long>> indexMap =
                    (Map<Object, HashSet<Long>>) this.hashes.get(columnName);

            if (indexMap == null) {
                throw new IndexException(
                        "Не найден индекс столбца: " + columnName
                );
            }

            HashSet<Long> offsets = indexMap.get(value);

            if (offsets != null) {
                result.addAll(offsets);
            }

            return result;
        }

        //Индекса нет — просматриваем файл.
        try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "r")){
            long fileLength = rf.length();

            for (long offset = 0;
                 offset < fileLength;
                 offset += DBRECORDSIZE)
            {
                rf.seek(offset);

                // Первый байт: существует ли строка.
                if (!rf.readBoolean()) {
                    continue;
                }

                Record record = readLine(offset);
                Object actualValue = record.getValues().get(columnName);

                if (value.equals(actualValue)) {
                    result.add(offset);
                }
            }
        }
        catch (IOException e)
        {
            throw new FileIntegrityException("Ошибка чтения таблицы: " + e.getMessage());
        }

        return result;
    }


    @SuppressWarnings("unchecked")
    private HashSet<Long> getLineOffset(Column column, Range<?> range)
    {
        if (column.isKey()){
            Map<Integer, Long> keyMap = (Map<Integer, Long>) this.hashes.get(column.getName());

            if (keyMap == null) {
                throw new PrimaryKeyIndexException("Не найден индекс первичного ключа: " + column.getName());
            }

            HashSet<Long> result = new HashSet<>();

            for (Map.Entry<Integer, Long> entry : keyMap.entrySet()){
                Integer id = entry.getKey();
                Long offset = entry.getValue();

                if (range.checkCondition(id)) {
                    result.add(offset);
                }
            }

            return result;
        }

        if (column.getType() != Column.DataType.INT && column.getType() != Column.DataType.DOUBLE) {
            throw new DatabaseOperationException(
                    "Числовой диапазон поддерживается только для INT и DOUBLE!"
            );
        }

        if (!column.isIndexed()){
            HashSet<Long> result = new HashSet<>();

            try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "r")){
                long fileLength = rf.length();

                for (long offset = 0; offset < fileLength; offset += DBRECORDSIZE){
                    rf.seek(offset);

                    // Пропускаем удалённые строки.
                    if (!rf.readBoolean()) {
                        continue;
                    }

                    Record record = readLine(offset);

                    Object actualValue = record.getValues().get(column.getName());

                    // NULL не входит в числовой диапазон.
                    if (actualValue == null) {
                        continue;
                    }

                    if (range.checkCondition((Number) actualValue)) {
                        result.add(offset);
                    }
                }
            }
            catch (IOException e){
                throw new FileIntegrityException("Ошибка чтения таблицы: " + e.getMessage());
            }

            return result;
        }

        Map<Object, HashSet<Long>> indexMap = (Map<Object, HashSet<Long>>) this.hashes.get(column.getName());

        if (indexMap == null) {
            throw new IndexException(
                    "Не найден индекс столбца: " + column.getName()
            );
        }

        HashSet<Long> result = new HashSet<>();

        for (Map.Entry<Object, HashSet<Long>> entry : indexMap.entrySet()){
            Number indexedValue = (Number) entry.getKey();

            if (range.checkCondition(indexedValue)) {
                result.addAll(entry.getValue());
            }
        }

        return result;
    }

    private void validateDataFile(){
        if (DBRECORDSIZE <= 0) {
            throw new FileIntegrityException("Некорректный размер записи: " + DBRECORDSIZE);
        }

        try (RandomAccessFile rf =new RandomAccessFile(DBFILEPATH, "r")){
            long fileLength = rf.length();

            if (fileLength % DBRECORDSIZE != 0) {
                throw new FileIntegrityException("Файл таблицы содержит неполную запись. " + "Размер файла: " + fileLength + " байт, размер записи: " + DBRECORDSIZE + " байт.");
            }
        }
        catch (IOException e)
        {
            throw new FileIntegrityException("Не удалось проверить файл таблицы: " + e.getMessage());
        }
    }

    private void rebuildPrimaryKeyIndex(){
        Column keyColumn = this.columns.get(0);
        String keyName = keyColumn.getName();

        HashMap<Integer, Long> keyMap = new HashMap<>();

        try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "r")){
            long fileLength = rf.length();

            for (long offset = 0; offset < fileLength; offset += DBRECORDSIZE){
                rf.seek(offset);

                // Удалённые строки не включаем в индекс
                if (!rf.readBoolean()) {
                    continue;
                }

                // Первичный ключ находится сразу после флага
                int id = rf.readInt();

                if (keyMap.containsKey(id)) {
                    throw new FileIntegrityException("В файле обнаружен повтор первичного ключа: " + id);
                }

                keyMap.put(id, offset);
            }
        }
        catch (IOException e){
            throw new FileIntegrityException("Не удалось восстановить первичный индекс: "+ e.getMessage());
        }

        // Устанавливаем индекс только после успешного чтения файла.
        this.hashes.put(keyName, keyMap);
        KEYFIELDNAME = keyName;
        keyColumn.setIndexed(true);
    }

    private void verifyNonKeyIndex(Column column){
        HashMap<Object, HashSet<Long>> expectedIndex = new HashMap<>();

        try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "r")){
            long fileLength = rf.length();

            for (long offset = 0; offset < fileLength; offset += DBRECORDSIZE){
                rf.seek(offset);

                // Удалённые строки не индексируем.
                if (!rf.readBoolean())
                    continue;

                Record record = readLine(offset);

                Object value = record.getValues().get(column.getName());

                // NULL в текущие индексы не записывается.
                if (value == null)
                    continue;

                expectedIndex.computeIfAbsent(value, k -> new HashSet<>()).add(offset);
            }
        }
        catch (IOException e){
            throw new FileIntegrityException("Не удалось проверить индекс столбца "+ column.getName()+ ": " + e.getMessage());
        }

        Object loadedIndex = this.hashes.get(column.getName());

        if (!expectedIndex.equals(loadedIndex)) {
            this.hashes.put(column.getName(), expectedIndex);
        }

        column.setIndexed(true);
    }

    public HashSet<Record> selectAll(){
        HashSet<Record> records = new HashSet<>();

        try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "r")){
            long fileLength = rf.length();

            for (long offset = 0; offset < fileLength; offset += DBRECORDSIZE){
                rf.seek(offset);

                // Пропускаем удалённые строки
                if (!rf.readBoolean())
                    continue;

                Record record = readLine(offset);
                records.add(record);
            }
        }
        catch (IOException e){
            throw new FileIntegrityException("Не удалось прочитать таблицу: " + e.getMessage());
        }

        return records;
    }

    public Column getColumn(String columnName){
        for (Column column : this.columns){
            if (column.getName().equals(columnName)) {
                return column;
            }
        }

        throw new DatabaseOperationException("Столбец не найден: " + columnName);
    }

    public ArrayList<Column> getColumns()
    {
        return new ArrayList<>(this.columns);
    }

    public void createIndex(String columnName){
        Column column = getColumn(columnName);

        if (column.isIndexed()) {
            throw new DatabaseOperationException("Столбец уже проиндексирован: " + columnName);
        }

        // Строит индекс по существующим строкам
        // и устанавливает column.setIndexed(true)
        verifyNonKeyIndex(column);
    }

    // Ищем смещение -> кидаем в эту функцию
    // Считывает одну строку из файла по предоставленному смещению
    // Если смещение больше текущей длины возвращает null
    private Record readLine(Long offset) throws DatabaseException
    {
        try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "r"))
        {
            // Возвращаем в случае попытки считывания еще не инициализированной информации
            if (offset >= rf.length()) {return null;}

            rf.seek(offset);
            boolean isAlive = rf.readBoolean();
            if (!isAlive) {throw new DatabaseException("Не удалось считать строчку по смещению. Строка была удалена.");}
            HashMap<String, Object> values = new HashMap<>();
            for (Column column : this.columns)
            {
                if (!column.isKey() && !rf.readBoolean()) {
                    rf.skipBytes(column.getSize());
                    continue;
                }
                String columnName = column.getName();
                switch (column.getType())
                {
                    case INT:
                        values.put(columnName, rf.readInt());
                        break;

                    case DOUBLE:
                        values.put(columnName, rf.readDouble());
                        break;

                    case BOOLEAN:
                        values.put(columnName, rf.readBoolean());
                        break;

                    case DATE:
                        values.put(columnName, rf.readLong());
                        break;
                    
                    case STRING:
                        byte[] nameBuffer = new byte[column.getSize()];
                        rf.readFully(nameBuffer);

                        int end = nameBuffer.length;

                        // Убираем только нулевые байты заполнения в конце.
                        while (end > 0 && nameBuffer[end - 1] == 0) { end--; }

                        String name = new String(nameBuffer, 0, end, StandardCharsets.UTF_8);
                        values.put(columnName, name);
                        break;

                    default: break;
                    }
            }
            return new Record(values);
        }
        catch (IOException e)
        {
            throw new DatabaseException("Не удалось считать строчку по смещению. Системная ошибка.");
        }
    }
}