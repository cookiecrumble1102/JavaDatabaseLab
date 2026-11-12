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


        // Задаем первое поле ключевым
        this.columns.get(0).setKey(true);


        // Здесь делаем проверку на наличие индексных файлов (какие существуют) и загружаем в оперативку
        for (int columnIndex = 0; columnIndex < this.columns.size(); columnIndex++)
        {
            Column thisColumn = columns.get(columnIndex);
            String columnName = thisColumn.getName();
            String indexPath = directoryPath + File.separator + name + "_" + columnName + ".idx";
            File currentFile = new File(indexPath);
            if (currentFile.exists())
            {
                // Если поле является ключевым
                if (columns.get(columnIndex).isKey())
                {
                    // Если не удалось сделать индексацию по ключевому полю, то выдаем ошибку
                    try
                    {
                        loadKeyIndex(thisColumn, indexPath);
                        KEYFIELDNAME = columnName;
                    }
                    catch (DatabaseException e)
                    {
                        System.err.println("Индексация ключевого поля не удалась!");
                        throw new PrimaryKeyIndexException("Не удалось произвести индексацию ключевого поля " + columnName + "!");
                    }
                }

                // Если поле не является ключевым
                else
                {
                    // Если удалось загрузить индексный файл, то устанавливаем флаг индексации, иначе отправляем Warning и не индексируем по этому полю
                    try
                    {
                        loadNonKeyIndex(thisColumn, indexPath);
                        this.columns.get(columnIndex).setIndexed(true);
                    }
                    catch (DatabaseException e)
                    {
                        System.err.println("Индексация поля не удалась!");
                    }
                }
            }
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
        try (RandomAccessFile rf = new RandomAccessFile(filePath, "rw"))
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
                        String name = new String(nameBuffer, StandardCharsets.UTF_8).trim();
                        key = name;
                        break;

                    default: 
                        key = null;
                        break;
                }

                int amount = rf.readInt();
                if (key != null)
                {
                    HashSet<Long> offsetSet = new HashSet<>();
                    for (int i = 0; i < amount; i++)
                    {
                        offsetSet.add(rf.readLong());
                    }
                    thisMap.put(key, offsetSet); // ИСПРАВЛЕНО: теперь сохраняется весь сет смещений, а не затирается последним
                }

                current += column.getSize() + 4 + amount*8;

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
        Long currentOffset = offset;
        for (Column column : this.columns)
        {
            if (column.getType() == Column.DataType.NONE) {continue;}

            String columnName = column.getName();
            Object searchResult = values.get(columnName);

            // Добавляем по отдельности
            addColumnToHash(column, searchResult, currentOffset);
            currentOffset += column.getSize();
        }
    }

    @SuppressWarnings("unchecked")
    private void addColumnToHash(Column column, Object value, Long offset) 
    {
        String columnName = column.getName();

        // Если аттрибут проиндексирован, то записываем к нему в хэш-табличку новое значение
        if (column.isIndexed() && value != null)
        {
            Map<Object, HashSet<Long>> thisMap = (Map<Object, HashSet<Long>>) this.hashes.get(columnName);
            if (thisMap != null) {
                thisMap.computeIfAbsent(value, k -> new HashSet<>()).add(offset);
            }
        }
    }

    // Удаление хэшированных записей
    private void removeFromIndex(Long offset)
    {
        Long currentOffset = offset;
        Record rec = readLine(currentOffset);
        if (rec == null) return;
        HashMap<String, Object> values = rec.getValues();
        for (Column column : this.columns)
        {
            if (column.getType() == Column.DataType.NONE) {continue;}

            String columnName = column.getName();
            Object searchResult = values.get(columnName);

            // Удаляем по отдельности
            removeColumnFromHash(column, searchResult, currentOffset);
            currentOffset += column.getSize();
        }
    }

    @SuppressWarnings("unchecked")
    private void removeColumnFromHash(Column column, Object value, Long offset) 
    {
        String columnName = column.getName();

        // Если аттрибут проиндексирован, то удаляем
        if (column.isIndexed() && value != null)
        {
            Map<Object, HashSet<Long>> thisMap = (Map<Object, HashSet<Long>>) this.hashes.get(columnName);
            if (thisMap != null && thisMap.containsKey(value))
            {
                thisMap.get(value).remove(offset);
            }
        }
    }


    // Вставка записи
    @SuppressWarnings("unchecked")
    public void insertLine(HashMap<String, Object> values) throws DatabaseOperationException
    {
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
                    byte[] stringBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                    byte[] stringBuffer = new byte[item.getSize()];

                    System.arraycopy(stringBytes, 0, stringBuffer, 0, Math.min(stringBytes.length, item.getSize()));

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
    public void updateLine(Column column, Object value)
    {
        // Заранее узнаем нужный оффсет для изменения
        Long columnOffset = 0L;
        Boolean wasFound = false;
        for (Column cl : this.columns)
        {
            if (cl.getName().equals(column.getName()))
            {
                wasFound = true;
                break;
            }
            columnOffset += cl.getSize()+1;
        }

        if (!wasFound) {throw new DatabaseOperationException("Не найдено аттрибута для удаления данных!");}

        try
        {
            HashSet<Long> offsets = getLineOffset(column, value);
            for (Long offset : offsets)
            {
                // Проходимся по offset-ам и меняем значение
                try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "rw"))
                {
                    rf.seek(offset+columnOffset);
                    removeColumnFromHash(column, value, offset+columnOffset);
                    writeColumn(column, value, rf);
                    addColumnToHash(column, value, offset+columnOffset);

                }
                catch (IOException e)
                {
                    throw new DatabaseOperationException("Не удалось произвести удаление! " + e.getLocalizedMessage());
                }    
            }
        }
        catch (DatabaseException e)
        {
            throw new DatabaseOperationException("Не найдено данных для удаления!");
        }
    }

    // Удаление строк
    public void deleteLine(Column column, Object value)
    {
        try
        {
            HashSet<Long> offsets = getLineOffset(column, value);
            for (Long offset : offsets)
            {
                // Проходимся по offset-ам и делаем мягкое удаление (просто перезаписываем флаг)
                try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "rw")) // ИСПРАВЛЕНО: "r" заменено на "rw", чтобы можно было записать boolean
                {
                    rf.seek(offset);
                    rf.writeBoolean(false);
                    removeFromIndex(offset);
                }
                catch (IOException e)
                {
                    throw new DatabaseOperationException("Не удалось произвести удаление! " + e.getLocalizedMessage());
                }    
            }
        }
        catch (DatabaseException e)
        {
            throw new DatabaseOperationException("Не найдено данных для удаления!");
        }
    }


    // Функции поиска

    // Линейный поиск по базе данных
    // Не делаем публичным, т.к всегда стараемся проводить индексируемый поиск, а не линейным
    private HashSet<Record> searchLinear(Column column, Object value)
    {
        // Проходится по файлу БД и выдает нужные строчки
        Long currentOffset = 0L;
        HashSet<Record> records = new HashSet<>();
        while (true)
        {
            try
            {
                Record searchResult = readLine(currentOffset);
                if (searchResult == null) {break;}
                Object val = searchResult.getValue(column.getName());
                if (val != null && val.equals(value)) {records.add(searchResult);}
            }
            catch (DatabaseException e)
            {
                // Игнорируем удаленные строки при линейном сканировании
            }
            currentOffset += DBRECORDSIZE; // ИСПРАВЛЕНО: добавлен шаг по размеру записи, чтобы не зависнуть в бесконечном цикле на offset=0
        }
        return records;
    }
    


    // Индексированные функции поиска

    // Общая функция поиска в базе данных
    public HashSet<Record> searchLine(Column column, Object value) throws DatabaseOperationException
    {
        
        if (column.isKey())
        {
            HashSet<Record> result = new HashSet<>();
            if (column.getType() != Column.DataType.INT) {throw new DatabaseOperationException("Ключевое поле таблицы инициализировано опасным типом данных. Поиск не может быть произведен.");}
            if (!(value instanceof Integer)) {throw new DatabaseOperationException("Передано неверное значение для поиска по ключевому полю. Поиск не может быть произведен.");}

            result.add(searchByKeyValue((Integer) value));
            return result;
        }
        else
        {
            // Если требуется выполнить поиск по Range
            if (value instanceof Range)
            {
                try
                {
                    Range<?> searchRange = (Range<?>) value;
                    return searchByNonKeyValue(column, searchRange);
                }
                catch (DatabaseException e)
                {
                    throw new DatabaseOperationException("Попытка произвести условный поиск для неподходящего типа данных!" + " " + e.getLocalizedMessage());
                }
            }
            else
            {
                return searchByNonKeyValue(column, value);
            }
        }
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
    private HashSet<Long> getLineOffset(Column column, Object value) throws DatabaseException
    {
        String thisName = column.getName();
        Map<Object, HashSet<Long>> thisMap = (Map<Object, HashSet<Long>>) this.hashes.get(thisName);

        if (thisMap == null) {
            throw new DatabaseException("Индекс для поля " + thisName + " не инициализирован!");
        }

        if (thisMap.containsKey(value))
        {
            return thisMap.get(value);
        }
        else
        {
            throw new DatabaseException("Такое значение по аттрибуту " + thisName + " не обнаружено!");
        }
    }


    @SuppressWarnings("unchecked")
    private HashSet<Long> getLineOffset(Column column, Range value) 
    {
        if (column.isKey() && column.getType() != Column.DataType.DOUBLE && column.getType() != Column.DataType.INT)
        {
            throw new DatabaseOperationException("Некорректное поле для поиска по условию!");
        }

        // Создаем массив смещений
        HashSet<Long> result = new HashSet<>();

        TreeMap<Number, HashSet<Long>> thisMap = (TreeMap<Number, HashSet<Long>>) this.hashes.get(column.getName());
        if (thisMap == null) {
            throw new DatabaseOperationException("Для поля " + column.getName() + " отсутствует TreeMap индекс!");
        }

        // Достаем объекты из Range
        Number low = value.getLow();
        Number high = value.getHigh();
        boolean includeLow = value.isLowIncluded();
        boolean includeHigh = value.isHighIncluded();

        // Если обе границы заданы, используем subMap
        SortedMap<Number, HashSet<Long>> subMap; // ИСПРАВЛЕНО: Number вместо Object для согласования с TreeMap

        if (low != null && high != null) 
        {
            subMap = thisMap.subMap(low, includeLow, high, includeHigh);
        } 
        else if (low != null) {
            subMap = thisMap.tailMap(low, includeLow);
        } 
        else if (high != null) {
            subMap = thisMap.headMap(high, includeHigh);
        } 
        else {
            throw new DatabaseException("Передан некорректный объект типа Range!");
        }

        // Собираем все смещения из найденного среза
        for (HashSet<Long> offsets : subMap.values()) 
        {
            if (offsets != null) {
                result.addAll(offsets);
            }
        }
            
        return result;
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
            Boolean isDeleted = rf.readBoolean();
            if (isDeleted == true) {throw new DatabaseException("Не удалось считать строчку по смещению. Строка была удалена.");}
            HashMap<String, Object> values = new HashMap<>();
            for (Column column : this.columns)
            {
                if (!column.isKey() && !rf.readBoolean()) {continue;}
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
                        String name = new String(nameBuffer, StandardCharsets.UTF_8).trim();
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