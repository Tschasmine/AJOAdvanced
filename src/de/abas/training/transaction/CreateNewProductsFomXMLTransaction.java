package de.abas.training.transaction;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;

import de.abas.erp.db.DbContext;
import de.abas.erp.db.Transaction;
import de.abas.erp.db.schema.part.Product;
import de.abas.erp.db.schema.part.ProductEditor;
import de.abas.erp.db.selection.Conditions;
import de.abas.erp.db.selection.SelectionBuilder;
import de.abas.erp.db.util.QueryUtil;
import de.abas.training.common.AbstractAjoAccess;

public class CreateNewProductsFomXMLTransaction extends AbstractAjoAccess{
	private final String XML_FILE = "win/tmp/productListToRead.xml";
	private final String LOG_FILE = "win/tmp/productListToRead.log";

	private BufferedWriter bufferedWriter = null;
	private DbContext ctx = null;
	private boolean rollBack = false;
	private Transaction transaction = null;
	private ProductEditor productEditor = null;

	@Override
	public void run(String[] args) {
		ctx = getDbContext();
		getsOrCreatesLogFile(LOG_FILE);
		SAXBuilder saxBuilder = new SAXBuilder();
		Document document = null;
		try {
			bufferedWriter = new BufferedWriter(new FileWriter(LOG_FILE));
			document = saxBuilder.build(XML_FILE);
			Element rootElement = document.getRootElement();
			if (isValidXML(rootElement)) {
				ouputRootElement(rootElement);
				outputAttributes(rootElement.getChild("recordSet"));
				beginTransaction();
				createsProductsIfNotExisting(rootElement.getChild("recordSet")
						.getChildren());
				rollBackIfNecessary();
				commit();
			}
			else {
				logInvalidXML();
			}
			logEndOfProgram();
		}
		catch (JDOMException e) {
			ctx.out().println(e.getMessage());
		}
		catch (IOException e) {
			ctx.out().println(e.getMessage());
		}
		finally {
			closeProductEditor();
		}
	}

	/**
	 * Instantiates and begins the transaction.
	 */
	private void beginTransaction() {
		transaction = ctx.getTransaction();
		transaction.begin();
	}

	/**
	 * Checks whether product already exists and sets value of rollBack
	 * accordingly.
	 * 
	 * @param dbContext The database context.
	 * @param bufferedWriter The BufferedWriter instance.
	 * @param rollBack Whether or not a roll back is necessary.
	 * @param record The current record element.
	 * @return Returns value of rollBack indicating whether a roll back is
	 * necessary.
	 * @throws IOException Exception thrown if an error occurred.
	 */
	private boolean
			checksWhetherProductExists(boolean rollBack, Element record)
					throws IOException {
		List<Attribute> recordAttributes = record.getAttributes();
		for (Attribute attribute : recordAttributes) {
			ctx.out().println(
					attribute.getName() + " - " + attribute.getValue());
			// if current 'record' element attribute is swd
			if (attribute.getName().equals("swd")) {
				ctx.out().println(
						"Checking whether product with swd "
								+ attribute.getValue() + " already exists");
				// if there is already a product with the same search word
				if (isRecordExisting(ctx, attribute.getValue())) {
					// the transaction has to be rolled back
					rollBack = true;
					bufferedWriter.write("Product with swd "
							+ attribute.getValue() + " already exists");
					bufferedWriter.newLine();
				}
			}
		}
		return rollBack;
	}

	/**
	 * Makes sure the ProductEditor instance is not active anymore.
	 */
	private void closeProductEditor() {
		if (productEditor != null) {
			if (productEditor.active()) {
				productEditor.abort();
			}
		}
	}

	/**
	 * Commits the transaction and logs the process.
	 * 
	 * @throws IOException Exception thrown if an error occurs.
	 */
	private void commit() throws IOException {
		if (!rollBack) {
			transaction.commit();
			ctx.out().println("commit");
			bufferedWriter.write("commit");
			bufferedWriter.newLine();
		}
	}

	/**
	 * Creates the products.
	 * 
	 * @param dbContext The database context.
	 * @param record The current record element from the XML file.
	 * @param productEditor The ProductEditor instance.
	 */
	private void createProduct(Element record, ProductEditor productEditor) {
		// gets all child elements of each 'record' element
		List<Element> recordChildren = record.getChildren();
		// iterates all child elements of each 'record' element
		for (Element recordChild : recordChildren) {
			if (recordChild.getName().equals("header")) {
				ctx.out().println("writing header");
				writeProductHeaderFields(recordChild, productEditor, ctx);
			}
			else if (recordChild.getName().equals("row")) {
				ctx.out().println("writing row");
				writeProductRowFields(recordChild, productEditor, ctx);
			}
		}
	}

	/**
	 * Checks whether each record's product is not already existing. Then
	 * creates product or sets rollback to true.
	 * 
	 * @param dbContext The database context.
	 * @param bufferedWriter The BufferedWriter instance.
	 * @param rollBack Whether or not to roll back.
	 * @param records The records from the XML file.
	 * @return Returns value of rollBack, to indicate whether or not a roll back
	 * is necessary.
	 * @throws IOException Exception thrown if an error occurs.
	 */
	private void createsProductsIfNotExisting(List<Element> records)
			throws IOException {
		for (Element record : records) {
			productEditor = ctx.newObject(ProductEditor.class);
			rollBack = checksWhetherProductExists(rollBack, record);
			if (rollBack == true) {
				// if rollBack is true the opened ProductEditor instance is
				// aborted and the foreach loop is cancelled
				productEditor.abort();
				break;
			}
			else {
				createProduct(record, productEditor);
			}
			// saves the new product
			productEditor.commit();
			// for testing the ProductEditor instance can be aborted
			// productEditor.abort();

			logProductCreation(productEditor.objectId().getSwd(), productEditor.objectId()
					.getIdno());
		}
	}

	/**
	 * Gets or if not existent creates log file.
	 * 
	 * @param logFile The log file.
	 */
	private void getsOrCreatesLogFile(String logFile) {
		File file = new File(logFile);
		if (!file.exists()) {
			try {
				boolean createNewFile = file.createNewFile();
				if (createNewFile) {
					ctx.out().println("File " + logFile + " was created");
				}
			}
			catch (IOException e) {
				ctx.out().println(e.getMessage());
			}
		}
	}

	/**
	 * Checks whether a product with the same search word exists.
	 * 
	 * @param dbContext The database context.
	 * @param swd The search word.
	 * @return Returns true if the a product with the search word exits, else
	 * returns false.
	 */
	private boolean isRecordExisting(DbContext dbContext, String swd) {
		SelectionBuilder<Product> selectionBuilder =
				SelectionBuilder.create(Product.class);
		selectionBuilder.add(Conditions.eq(Product.META.swd, swd));
		Product first = QueryUtil.getFirst(dbContext, selectionBuilder.build());
		if (first == null) {
			return false;
		}
		else {
			return true;
		}
	}

	/**
	 * Checks whether the XML file is valid.
	 * 
	 * @param rootElement The root element
	 * @return True, if XML is valid, else false.
	 */
	private boolean isValidXML(Element rootElement) {
		return rootElement.getName().equals("abasData");
	}

	/**
	 * Logs the commit process.
	 * 
	 * @param dbContext The database context.
	 * @param bufferedWriter The BufferedWriter instance.
	 * @param swd The search word.
	 * @param idno The identification number.
	 * @throws IOException The exception thrown if an error occurs.
	 */
	private void logProductCreation(String swd, String idno) throws IOException {
		// logs the process
		ctx.out().println("commit and log ----------------->");
		bufferedWriter.write(swd + " - " + idno);
		bufferedWriter.newLine();
	}

	/**
	 * Logs the end of the program.
	 * 
	 * @throws IOException Exception thrown if an error occurs.
	 */
	private void logEndOfProgram() throws IOException {
		bufferedWriter.write("end of program");
		bufferedWriter.newLine();
		bufferedWriter.close();
	}

	/**
	 * Logs error message if XML is invalid.
	 * 
	 * @throws IOException Exception thrown if an error occurs.
	 */
	private void logInvalidXML() throws IOException {
		ctx.out().println("invalid abas xml format");
		bufferedWriter.write("invalid abas xml format");
		bufferedWriter.newLine();
	}

	/**
	 * Outputs the root element.
	 * 
	 * @param rootElement The root element.
	 */
	private void ouputRootElement(Element rootElement) {
		ctx.out().println("root-Element: " + rootElement.getName());
	}

	/**
	 * Outputs the attributes of the recordSet element.
	 * 
	 * @param recordSet The recordSet element.
	 */
	private void outputAttributes(Element recordSet) {
		for (Attribute attribute : recordSet.getAttributes()) {
			ctx.out().println(
					attribute.getName() + " - " + attribute.getValue());
		}
	}

	/**
	 * Rolls the transaction back if necessary and logs the process.
	 * 
	 * @throws IOException Exception thrown if an error occurs.
	 */
	private void rollBackIfNecessary() throws IOException {
		if (rollBack) {
			transaction.rollback();
			ctx.out().println("rollback");
			bufferedWriter.write("rollback");
			bufferedWriter.newLine();
		}
	}

	/**
	 * Reads the information about every product's head from the XML file and
	 * creates a product accordingly.
	 * 
	 * @param recordChild The child element of the 'record' element containing
	 * information about the record's head.
	 * @param productEditor The ProductEditor instance.
	 * @param dbContext The database context.
	 */
	private void writeProductHeaderFields(Element recordChild,
			ProductEditor productEditor, DbContext dbContext) {
		// gets the head fields as child elements of the 'header' element
		List<Element> fields = recordChild.getChildren();
		for (Element field : fields) {
			String fieldName = field.getAttributeValue("name");
			String fieldValue = field.getValue();
			dbContext.out().println(fieldName + " - " + fieldValue);
			productEditor.setString(fieldName, fieldValue);
		}
	}

	/**
	 * Reads the information about every product's row from the XML file and
	 * creates table rows accordingly.
	 * 
	 * @param recordChild The child element of the 'record' element containing
	 * information about the record's table rows.
	 * @param productEditor The ProductEditor instance.
	 * @param dbContext The database context.
	 */
	private void writeProductRowFields(Element recordChild,
			ProductEditor productEditor, DbContext dbContext) {
		// gets the row fields as child elements of the 'row' element
		List<Element> fields = recordChild.getChildren();
		ProductEditor.Row appendRow = productEditor.table().appendRow();
		for (Element field : fields) {
			String fieldName = field.getAttributeValue("name");
			String fieldValue = field.getValue();
			dbContext.out().println(fieldName + " - " + fieldValue);
			appendRow.setString(fieldName, fieldValue);
		}
	}
}