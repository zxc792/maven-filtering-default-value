package com.netease.maven.filtering;

import java.io.FilterReader;
import java.io.Reader;
import java.util.LinkedHashSet;

import org.codehaus.plexus.interpolation.multi.DelimiterSpecification;

public class AbstractEnhancerFilterReaderLineEnding extends FilterReader{
	  private String escapeString;

	    /**
	     * using escape or not.
	     */
	    protected boolean useEscape = false;

	    /**
	     * if true escapeString will be preserved \{foo} -> \{foo}
	     */
	    private boolean preserveEscapeString = false;

	    protected LinkedHashSet<DelimiterSpecification> delimiters = new LinkedHashSet<DelimiterSpecification>();

	    /**
	     * must always be bigger than escape string plus delimiters, but doesn't need to be exact
	     */
	    protected int markLength = 4096;

	    protected AbstractEnhancerFilterReaderLineEnding( Reader in )
	    {
	        super( in );
	    }

	    /**
	     * @return the escapce string.
	     */
	    public String getEscapeString()
	    {
	        return escapeString;
	    }

	    /**
	     * @param escapeString Set the value of the escape string.
	     */
	    public void setEscapeString( String escapeString )
	    {
	        // TODO NPE if escapeString is null ?
	        if ( escapeString != null && escapeString.length() >= 1 )
	        {
	            this.escapeString = escapeString;
	            this.useEscape = escapeString != null && escapeString.length() >= 1;
	            calculateMarkLength();
	        }
	    }

	    /**
	     * @return state of preserve escape string.
	     */
	    public boolean isPreserveEscapeString()
	    {
	        return preserveEscapeString;
	    }

	    /**
	     * @param preserveEscapeString preserve escape string {@code true} or {@code false}.
	     */
	    public void setPreserveEscapeString( boolean preserveEscapeString )
	    {
	        this.preserveEscapeString = preserveEscapeString;
	    }

	    protected void calculateMarkLength()
	    {
	        markLength = 4096;

	        if ( escapeString != null )
	        {

	            markLength += escapeString.length();

	        }
	        for ( DelimiterSpecification spec : delimiters )
	        {
	            markLength += spec.getBegin().length();
	            markLength += spec.getEnd().length();

	        }
	    }

}
